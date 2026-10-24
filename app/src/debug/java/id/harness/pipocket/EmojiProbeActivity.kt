package id.harness.pipocket

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import id.harness.pipocket.ui.PiPocketTheme

/**
 * **debug 专用**：emoji 字体渲染对照页（release 不含）。
 *
 * 起因：手机上有会话回复里的 👋 被渲染成两个方框（数据本身是好的 UTF-8）。
 * 这里把 Compose 的 Default / SansSerif / Serif 与平台 TextView 摆在一起，用截图判定谁缺字形。
 *
 *   adb shell am start -n id.harness.pipocket/.EmojiProbeActivity
 */
class EmojiProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1) 平台 TextView（系统字体回退链的基准）
        root.addView(TextView(this).apply {
            text = "平台 TextView: 你好 👋 🚀 🇨🇳 ✓"
            textSize = 18f
            setPadding(24, 24, 24, 24)
        })

        // 2-4) Compose 三种字体族
        root.addView(ComposeView(this).apply {
            setContent {
                PiPocketTheme {
                    Scaffold { padding ->
                        Column(
                            modifier = Modifier.padding(padding).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Probe("Default", FontFamily.Default)
                            Probe("SansSerif", FontFamily.SansSerif)
                            Probe("Serif", FontFamily.Serif)
                            Probe("Monospace", FontFamily.Monospace)
                        }
                    }
                }
            }
        })

        setContentView(root)
    }

    @Composable
    private fun Probe(name: String, family: FontFamily) {
        Text(
            text = "$name: 你好 👋 🚀 🇨🇳 ✓",
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family),
        )
    }
}
