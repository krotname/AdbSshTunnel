package name.krot.adbsshtunnel;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bounded recovery if the independent native watcher exits, also while SSH is off. */
public final class RootMaintenanceJob extends JobService {
    private static final int JOB_ID = 5555;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> request;
    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(new JobInfo.Builder(JOB_ID,
            new ComponentName(context, RootMaintenanceJob.class)).setPeriodic(15 * 60 * 1000L)
            .setPersisted(true).build());
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        if (!RootSettings.configured(this)) return false;
        request = worker.submit(() -> {
            try {
                RootHelper.maintain(this);
                Settings.prefs(this).edit().remove("root_controller_error").apply();
            } catch (Exception e) {
                Settings.prefs(this).edit().putString("root_controller_error", e.getMessage()).apply();
            } finally { jobFinished(parameters, false); }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        if (request != null) request.cancel(true);
        return false;
    }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
