package io.modelbridge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ViewModels
import io.modelbridge.core.policy.Policy
import java.util.UUID

/** 文案、字段与规则对照原版 ProfileActivity；导入仅预填，确认保存后才生效。 */
@Composable
fun PolicyScreen(vms: ViewModels, onBack: () -> Unit) {
    val policies by vms.policies.collectAsState()
    val context = LocalContext.current
    var editor by remember { mutableStateOf<Policy?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                editor = io.modelbridge.app.readPolicyDraft(context, uri)
            } catch (error: Exception) {
                vms.toast("无法导入策略文件")
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("策略", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppStyle.TitleText)
        Text(
            "按模型注入正文，转发时前置到请求里",
            fontSize = 13.sp,
            color = AppStyle.SubtitleText,
            modifier = Modifier.padding(top = 5.dp, bottom = 12.dp),
        )
        GroupTitle("策略库")
        SettingsGroup {
            if (policies.isEmpty()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("还没有策略", fontSize = 15.sp, color = AppStyle.BodyText)
                    Text(
                        "复制一段策略正文后点下方「新建策略」，绑定模型 ID",
                        fontSize = 13.sp,
                        color = AppStyle.SubtitleText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            policies.forEachIndexed { index, policy ->
                if (index > 0) RowDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).clickable { editor = policy }.padding(end = 8.dp)) {
                        Text(policy.name, fontSize = 15.sp, color = AppStyle.BodyText)
                        val binding = policy.models.ifEmpty { listOf("未绑定模型") }.joinToString("、")
                        Text(
                            "$binding · ${policy.instruction.encodeToByteArray().size} 字节" +
                                if (policy.enabled) "" else " · 已停用",
                            fontSize = 12.sp,
                            color = AppStyle.SubtitleText,
                        )
                    }
                    Switch(checked = policy.enabled, onCheckedChange = { vms.togglePolicy(policy, it) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = { editor = Policy(UUID.randomUUID().toString(), "") }) { Text("新建策略") }
            OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/octet-stream")) }) {
                Text("从文件导入")
            }
        }
        GroupTitle("命中规则")
        SettingsGroup {
            Text(
                POLICY_HELP,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(16.dp),
            )
        }
        GroupTitle("导入")
        SettingsGroup {
            Text(
                "点「从文件导入」选一个 .md 或 .txt，整篇作为一条策略、文件名作为名称，再确认后保存。\n\n导入后停在编辑页，可以改名称、绑模型。模型留空就是通用策略，对所有转发的模型生效。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    editor?.let { draft ->
        PolicyEditor(
            draft = draft,
            existing = policies.any { it.id == draft.id },
            onDismiss = { editor = null },
            onSave = { vms.savePolicy(it) { editor = null } },
            onDelete = { vms.deletePolicy(draft) { editor = null } },
        )
    }
}

@Composable
private fun PolicyEditor(
    draft: Policy,
    existing: Boolean,
    onDismiss: () -> Unit,
    onSave: (Policy) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var models by remember(draft.id) { mutableStateOf(draft.models.joinToString(", ")) }
    var instruction by remember(draft.id) { mutableStateOf(draft.instruction) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing) "编辑策略" else "新建策略", fontSize = 18.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PolicyTextInput("策略名称", name, { name = it }, singleLine = true)
                PolicyTextInput("绑定模型（留空 = 通用，对所有模型生效）", models, { models = it })
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("策略正文（可直接粘贴）", fontSize = 12.5.sp, color = AppStyle.SubtitleText, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.getText()?.let { instruction = it.text } }) { Text("粘贴") }
                    TextButton(onClick = { instruction = "" }) { Text("清空") }
                }
                PolicyTextInput(
                    label = "",
                    text = instruction,
                    onChange = { instruction = it },
                    modifier = Modifier.heightIn(min = 200.dp, max = 280.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && instruction.isNotBlank(),
                onClick = {
                    val ids = models.split(',', '，', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
                    onSave(draft.copy(name = name.trim(), models = ids, instruction = instruction))
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (existing && !draft.builtin) {
                    TextButton(onClick = { confirmDelete = true }) { Text("删除", color = AppStyle.Accent) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除策略") },
            text = { Text("删除「${draft.name}」？绑定该策略的账号将不再注入它。") },
            confirmButton = { TextButton(onClick = onDelete) { Text("删除", color = AppStyle.Accent) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PolicyTextInput(
    label: String,
    text: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    if (label.isNotEmpty()) {
        Text(label, fontSize = 12.5.sp, color = AppStyle.SubtitleText, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    }
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color(0x0D000000),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = 14.sp, color = AppStyle.BodyText),
            cursorBrush = SolidColor(AppStyle.BodyText),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private const val POLICY_HELP =
    "命中规则：按「映射后的实际上游模型 ID」精确匹配；未绑定模型的策略是通用策略，对所有模型生效，但优先级低于精确命中。一次请求最多用一条策略，不做拼接。\n\n" +
        "每条的开关独立生效：停用后不再参与自动匹配，上游固定绑定的那条也会跳过注入。\n\n" +
        "三个协议的注入位置不同：chat 作为独立 system 消息前置；responses 写进 instructions；messages 写进 system。客户端原有内容一律保留。\n\n" +
        "策略正文只存在本机，不会上传。"
