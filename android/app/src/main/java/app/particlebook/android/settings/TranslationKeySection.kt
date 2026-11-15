package app.particlebook.android.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.Radius
import app.particlebook.android.ui.Spacing

/**
 * 设置页「翻译」分区（A023-3/4）。
 *
 * 只接收 [TranslationKeyController]，不依赖 AppRoot / SettingsScreen 内部状态，
 * 由 Lead 在 SettingsScreen 的 LazyColumn 中一行接线：
 * item { TranslationKeySection(remember { TranslationKeyController(
 *     SettingsSecretBridge(AndroidSecretStore(context.applicationContext))) }) }
 *
 * 视觉遵循 SettingsScreen §17.4：28dp 分组圆角、1dp outlineVariant 边框、行高 ≥64dp。
 * 页面不显示 Key 明文；Key 输入框使用 password 视觉变换，且不写入 rememberSaveable。
 */
@Composable
fun TranslationKeySection(controller: TranslationKeyController) {
    var status by remember(controller) { mutableStateOf(controller.status()) }
    var feedback by remember { mutableStateOf<TranslationFeedback?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }
    var clearDialogOpen by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }

    fun refresh() {
        status = controller.status()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        Text(
            text = "翻译",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = Spacing.lg, bottom = Spacing.xs),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.group),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable {
                            keyInput = ""
                            feedback = null
                            dialogOpen = true
                        }
                        .padding(horizontal = 20.dp, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "翻译 API Key",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = status.message
                                ?: "Key 只保存在本机 Keystore，不写入设置导出或备份",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = status.shortLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.isCorrupted) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                    Spacer(Modifier.size(Spacing.xxs))
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val result = controller.test()
                            feedback = TranslationFeedback(
                                text = buildString {
                                    append(result.message)
                                    result.translatedText?.let { append("：").append(it) }
                                },
                                ok = result.ok,
                            )
                        },
                    ) {
                        Text("测试翻译")
                    }
                    if (status.state != TranslationKeyState.NOT_CONFIGURED) {
                        TextButton(
                            onClick = { clearDialogOpen = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("清除")
                        }
                    }
                }
            }
        }
        feedback?.let { item ->
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(start = 4.dp, top = Spacing.xs, end = 4.dp),
            )
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = {
                dialogOpen = false
                keyInput = ""
            },
            title = {
                Text(if (status.isConfigured) "更换 API Key" else "设置 API Key")
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Text(
                        text = "保存后可在「测试翻译」验证；Key 不会出现在设置/备份/日志中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = keyInput.isNotBlank(),
                    onClick = {
                        val result = controller.save(keyInput)
                        refresh()
                        feedback = TranslationFeedback(result.message, result.ok)
                        dialogOpen = false
                        keyInput = ""
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dialogOpen = false
                        keyInput = ""
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (clearDialogOpen) {
        AlertDialog(
            onDismissRequest = { clearDialogOpen = false },
            title = { Text("清除 API Key") },
            text = { Text("清除后翻译入口会回到「未配置」，需要重新输入 Key。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val result = controller.clear()
                        refresh()
                        feedback = TranslationFeedback(result.message, result.ok)
                        clearDialogOpen = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearDialogOpen = false }) {
                    Text("取消")
                }
            },
        )
    }
}

private data class TranslationFeedback(
    val text: String,
    val ok: Boolean,
)
