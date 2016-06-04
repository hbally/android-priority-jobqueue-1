package com.birbit.android.jobqueue.test.jobmanager;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.birbit.android.jobqueue.CancelReason;
import com.birbit.android.jobqueue.Job;
import com.birbit.android.jobqueue.JobManager;
import com.birbit.android.jobqueue.Params;
import com.birbit.android.jobqueue.RetryConstraint;
import com.birbit.android.jobqueue.config.Configuration;
import com.birbit.android.jobqueue.network.NetworkUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(constants = com.birbit.android.jobqueue.BuildConfig.class)
public class DeadlineTest extends JobManagerTestBase {
    private final boolean persistent;
    private final boolean reqNetwork;
    private final boolean reqUnmeteredNetwork;
    private final long delay;
    private final boolean cancelOnDeadline;

    public DeadlineTest(boolean persistent, boolean reqNetwork, boolean reqUnmeteredNetwork,
                        long delay, boolean cancelOnDeadline) {
        this.persistent = persistent;
        this.reqNetwork = reqNetwork;
        this.reqUnmeteredNetwork = reqUnmeteredNetwork;
        this.delay = delay;
        this.cancelOnDeadline = cancelOnDeadline;
    }

    @ParameterizedRobolectricTestRunner.Parameters(name =
            "persistent: {0} reqNetwork: {1} reqUnmetered: {2} delay: {3} cancelOnDeadline: {4}")
    public static List<Object[]> getParams() {
        List<Object[]> params = new ArrayList<>();
        for (long delay : new long[]{0, 10}) {
            for (int i = 0; i < 16; i++) {
                // need a network requirement
                if ((i & 4) != 4 && (i & 2) != 2) {
                    continue;
                }
                params.add(new Object[] {
                        (i & 1) == 1, // persistent
                        (i & 2) == 2, // reqNetwork
                        (i & 4) == 4, // reqUnmeteredNetwork
                        delay,
                        (i & 8) == 8, // cancelOnDeadline
                });
            }
        }
        return params;
    }

    @Before
    public void clear() {
        DeadlineJob.clear();
    }

    @Test
    public void deadlineTest() throws Exception {
        DummyNetworkUtil networkUtil = new DummyNetworkUtilWithConnectivityEventSupport();
        JobManager jobManager = createJobManager(
                new Configuration.Builder(RuntimeEnvironment.application)
                        .networkUtil(networkUtil)
                        .timer(mockTimer));
        networkUtil.setNetworkStatus(NetworkUtil.DISCONNECTED);
        Params params = new Params(0).setPersistent(persistent).setRequiresNetwork(reqNetwork)
                .setRequiresUnmeteredNetwork(reqUnmeteredNetwork)
                .delayInMs(delay);
        if (cancelOnDeadline) {
            params.overrideDeadlineToCancelInMs(200);
        } else {
            params.overrideDeadlineToRunInMs(200);
        }
        DeadlineJob job = new DeadlineJob(params);
        jobManager.addJob(job);
        assertThat(DeadlineJob.runLatch.await(3, TimeUnit.SECONDS), is(false));
        assertThat(DeadlineJob.cancelLatch.await(1, TimeUnit.MILLISECONDS), is(false));
        mockTimer.incrementMs(200);
        if (cancelOnDeadline) {
            assertThat(DeadlineJob.cancelLatch.await(3, TimeUnit.SECONDS), is(true));
            assertThat(DeadlineJob.runLatch.await(1, TimeUnit.SECONDS), is(false));
            assertThat(DeadlineJob.cancelReason, is(CancelReason.REACHED_DEADLINE));
        } else {
            assertThat(DeadlineJob.runLatch.await(3, TimeUnit.SECONDS), is(true));
            assertThat(DeadlineJob.cancelLatch.await(1, TimeUnit.SECONDS), is(false));
            assertThat(DeadlineJob.cancelReason, is(-1));
        }
    }

    public static class DeadlineJob extends Job {
        static CountDownLatch runLatch;
        static CountDownLatch cancelLatch;
        static int cancelReason;
        static {
            clear();
        }
        static void clear() {
            runLatch = new CountDownLatch(1);
            cancelLatch = new CountDownLatch(1);
            cancelReason = -1; // just a dummy one
        }
        protected DeadlineJob(Params params) {
            super(params);
        }

        @Override
        public void onAdded() {

        }

        @Override
        public void onRun() throws Throwable {
            runLatch.countDown();
        }

        @Override
        protected void onCancel(@CancelReason int cancelReason, @Nullable Throwable throwable) {
            DeadlineJob.cancelReason = cancelReason;
            cancelLatch.countDown();
        }

        @Override
        protected RetryConstraint shouldReRunOnThrowable(@NonNull Throwable throwable, int runCount, int maxRunCount) {
            return RetryConstraint.CANCEL;
        }
    }
}