package com.everything.eve.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.ServiceLocator
import com.everything.eve.data.identity.Identity
import com.everything.eve.data.identity.IdentityRepository
import com.everything.eve.finance.IdentityBackOcrHint
import com.everything.eve.finance.IdentityFrontOcrHint
import com.everything.eve.finance.parseIdentityBackText
import com.everything.eve.finance.parseIdentityFrontText
import com.everything.eve.ui.components.VaultImageSlot
import com.everything.eve.ui.vault.TextOcrScannerSheet
import com.everything.eve.vault.sha256HexOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityEditorScreen(
    identityId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repo = ServiceLocator.identityRepo
    val attachmentRepo = ServiceLocator.attachmentRepo

    var id by remember { mutableStateOf(identityId ?: repo.newId()) }
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("id_card") }
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var issuer by remember { mutableStateOf("") }
    var issuedOn by remember { mutableStateOf("") }
    var expiresOn by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var frontId by remember { mutableStateOf<String?>(null) }
    var backId by remember { mutableStateOf<String?>(null) }
    var createdAt by remember { mutableStateOf(0L) }
    var showFrontOcr by remember { mutableStateOf(false) }
    var showBackOcr by remember { mutableStateOf(false) }

    LaunchedEffect(identityId) {
        if (identityId != null) {
            val e = repo.getById(identityId)
            if (e != null) {
                id = e.id
                title = e.title
                kind = e.kind
                name = e.name ?: ""
                number = e.number ?: ""
                issuer = e.issuer ?: ""
                issuedOn = e.issuedOn ?: ""
                expiresOn = e.expiresOn ?: ""
                notes = e.notes ?: ""
                frontId = e.frontAttachmentId
                backId = e.backAttachmentId
                createdAt = e.createdAt
            }
        }
    }

    suspend fun uploadSide(bytes: ByteArray, mime: String): Result<String> {
        val sha = sha256HexOf(bytes)
        val ref = attachmentRepo.upload(
            parentRefId = id,
            content = bytes,
            mime = mime,
            sha256Hex = sha,
            parentModule = IdentityRepository.MODULE_IDENTITY,
            name = "identity-image",
        )
        return ref.map { it.id }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_editor_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.identity_field_title)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = kind,
                onValueChange = { kind = it },
                label = { Text(stringResource(R.string.identity_field_kind)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.identity_field_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text(stringResource(R.string.identity_field_number)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { showFrontOcr = true },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.identity_ocr_scan_front))
            }
            OutlinedTextField(
                value = expiresOn,
                onValueChange = { expiresOn = it },
                label = { Text(stringResource(R.string.identity_field_expires)) },
                modifier = Modifier.fillMaxWidth(),
            )
            VaultImageSlot(
                label = stringResource(R.string.identity_image_front),
                attachmentId = frontId,
                onAttachmentIdChange = { frontId = it },
                loadPreview = { aid -> attachmentRepo.download(aid) },
                onUpload = { bytes, mime -> uploadSide(bytes, mime) },
                modifier = Modifier.padding(top = 12.dp),
            )
            VaultImageSlot(
                label = stringResource(R.string.identity_image_back),
                attachmentId = backId,
                onAttachmentIdChange = { backId = it },
                loadPreview = { aid -> attachmentRepo.download(aid) },
                onUpload = { bytes, mime -> uploadSide(bytes, mime) },
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedButton(onClick = { showBackOcr = true }) {
                Text(stringResource(R.string.identity_ocr_scan_back))
            }
            if (showFrontOcr) {
                TextOcrScannerSheet(
                    titleRes = R.string.identity_ocr_front_title,
                    noResultRes = R.string.identity_ocr_no_result,
                    parse = ::parseIdentityFrontText,
                    preview = { hint: IdentityFrontOcrHint ->
                        hint.name?.let { Text(stringResource(R.string.identity_field_name) + "：" + it) }
                        hint.number?.let { n ->
                            val masked = if (n.length > 8) n.take(4) + "****" + n.takeLast(4) else n
                            Text(stringResource(R.string.identity_field_number) + "：" + masked)
                        }
                    },
                    onApply = { hint ->
                        hint.name?.let { name = it }
                        hint.number?.let { number = it }
                        showFrontOcr = false
                    },
                    onDismiss = { showFrontOcr = false },
                    testTagPrefix = "identity_front_ocr",
                )
            }
            if (showBackOcr) {
                TextOcrScannerSheet(
                    titleRes = R.string.identity_ocr_back_title,
                    noResultRes = R.string.identity_ocr_no_result,
                    parse = ::parseIdentityBackText,
                    preview = { hint: IdentityBackOcrHint ->
                        hint.issuer?.let { Text("签发机关：$it") }
                        hint.expiresOn?.let { Text(stringResource(R.string.identity_field_expires) + "：$it") }
                    },
                    onApply = { hint ->
                        hint.issuer?.let { issuer = it }
                        hint.validFrom?.let { issuedOn = it }
                        hint.expiresOn?.let { expiresOn = it }
                        showBackOcr = false
                    },
                    onDismiss = { showBackOcr = false },
                    testTagPrefix = "identity_back_ocr",
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val identity = Identity(
                            id = id,
                            title = title.trim(),
                            kind = kind.trim().ifBlank { "generic" },
                            name = name.takeIf { it.isNotBlank() },
                            number = number.takeIf { it.isNotBlank() },
                            issuer = issuer.takeIf { it.isNotBlank() },
                            issuedOn = issuedOn.takeIf { it.isNotBlank() },
                            expiresOn = expiresOn.takeIf { it.isNotBlank() },
                            notes = notes.takeIf { it.isNotBlank() },
                            frontAttachmentId = frontId,
                            backAttachmentId = backId,
                            createdAt = if (createdAt > 0) createdAt else now,
                            updatedAt = now,
                        )
                        repo.upsert(identity)
                        onDone()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
