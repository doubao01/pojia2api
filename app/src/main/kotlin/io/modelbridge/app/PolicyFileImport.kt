package io.modelbridge.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.modelbridge.core.policy.Policy
import java.util.UUID

/** 系统文件选择器返回后只构造草稿，不自动启用或写入策略库。 */
fun readPolicyDraft(context: Context, uri: Uri): Policy {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "导入策略"
    val source = resolver.openInputStream(uri) ?: error("无法打开策略文件")
    val bytes = source.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_POLICY_BYTES) { "策略文件超过 1 MB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    val instruction = bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
    require(instruction.isNotBlank()) { "策略正文不能为空" }
    return Policy(
        id = UUID.randomUUID().toString(),
        name = name.substringBeforeLast('.').ifBlank { name },
        instruction = instruction,
    )
}

private const val MAX_POLICY_BYTES = 1024 * 1024
