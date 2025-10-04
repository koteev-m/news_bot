package billing.recon

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import repo.BillingReconRepository

fun main() = runBlocking {
    val job = BillingReconJob(BillingReconRepository())
    val res = job.run()
    val report = "runId=${'$'}{res.runId} status=${'$'}{res.status} counts=${'$'}{res.counts}"
    println(report)
    Files.writeString(Path.of("recon_result.txt"), report + System.lineSeparator(), StandardCharsets.UTF_8)
}
