package vn.supra.pdalauncher;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class LauncherDiagnosticJobService extends JobService {
    @Override public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                if (params.getJobId() == 12910330 || params.getJobId() == 12910331) {
                    LauncherDiagnostics.runScheduledUpload(getApplicationContext());
                } else {
                    LauncherDiagnostics.runPeriodicMaintenance(getApplicationContext());
                }
            } finally {
                jobFinished(params, false);
            }
        }, "supra-launcher-diagnostic-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }
}
