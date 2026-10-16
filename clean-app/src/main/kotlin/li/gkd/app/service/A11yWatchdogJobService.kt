package li.gkd.app.service

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * CLEAN：无障碍守护的周期任务。
 *
 * 这是**唯一能在应用进程被杀之后把进程重新拉起来**的自检路径（JobScheduler 持久化周期任务），
 * 也是"服务被系统关掉、用户半天不知道"这种情况的主要兜底。
 *
 * 任务本身不联网、不写库，只做一次同步状态检查，所以直接返回 `false`（已处理完毕）。
 */
class A11yWatchdogJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        A11yWatchdog.runCheck(this, "job")
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
