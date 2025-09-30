package alerts.metrics

interface AlertMetricsPort {
    fun incPush()
    fun incBudgetReject()

    companion object {
        val Noop: AlertMetricsPort = object : AlertMetricsPort {
            override fun incPush() {
            }

            override fun incBudgetReject() {
            }
        }
    }
}
