package com.xjyzs.operator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xjyzs.operator.ui.theme.OperatorTheme

class DialogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        setContent {
            OperatorTheme {
                DialogActivityUI(title,text)
            }
        }
    }
}

@Composable
fun DialogActivityUI(title: String,text:String) {
    val context = LocalContext.current
    AlertDialog(
        {
            (context as ComponentActivity).finish()
        },confirmButton = { TextButton({(context as ComponentActivity).finish()}){Text(stringResource(R.string.confirm))} },
        title = {Text(title)},
        text={ Text(text) })
}