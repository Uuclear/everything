package com.everything.eve.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.ServiceLocator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onLoggedOut: () -> Unit, vm: VaultViewModel = viewModel()) {
    val unlocked by ServiceLocator.auth.isUnlocked.collectAsState()

    if (!unlocked) {
        UnlockDialog(onUnlocked = { vm.syncNow() }, onLoggedOut = onLoggedOut)
        return
    }

    val notes by vm.notes.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("加密笔记") },
                actions = {
                    TextButton(onClick = { vm.syncNow() }) { Text("同步") }
                    TextButton(onClick = {
                        ServiceLocator.auth.logout()
                        onLoggedOut()
                    }) { Text("退出") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(notes, key = { it.id }) { note ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(note.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        if (note.body.isNotBlank()) {
                            Text(note.body, modifier = Modifier.padding(top = 4.dp))
                        }
                        Text(
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(note.updatedAt)),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateNoteDialog(
            onDismiss = { showCreate = false },
            onConfirm = { title, body ->
                vm.createNote(title, body)
                showCreate = false
            },
        )
    }
}

@Composable
private fun UnlockDialog(onUnlocked: () -> Unit, onLoggedOut: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = {},
        title = { Text("资料库已锁定") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("主密码（${ServiceLocator.auth.username}）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    try {
                        ServiceLocator.auth.login(
                            ServiceLocator.auth.username,
                            password,
                            "android-unlock",
                        )
                        error = null
                        onUnlocked()
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            }) { Text("解锁") }
        },
        dismissButton = { TextButton(onClick = onLoggedOut) { Text("切换账户") } },
    )
    error?.let { Text(it) }
}

@Composable
private fun CreateNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建加密笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text("正文") }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title, body) }) {
                Text("加密保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
