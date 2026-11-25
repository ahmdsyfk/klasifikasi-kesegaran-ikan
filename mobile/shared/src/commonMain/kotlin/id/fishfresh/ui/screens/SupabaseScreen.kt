package id.fishfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.fishfresh.presentation.BackendViewModel
import id.fishfresh.ui.components.Hairline
import id.fishfresh.ui.components.KeyValue
import id.fishfresh.ui.components.Notice
import id.fishfresh.ui.components.PlainIcon
import id.fishfresh.ui.components.PrimaryButton
import id.fishfresh.ui.components.SecondaryButton
import id.fishfresh.ui.components.Section
import id.fishfresh.ui.theme.Palette
import id.fishfresh.ui.theme.Space

/**
 * Konfigurasi Supabase: tujuan unggah data primer. Model diambil dari GitHub Releases, bukan Supabase.
 * Hanya publishable/anon key yang diterima; key selalu ditampilkan tersamar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SupabaseSections(vm: BackendViewModel) {
    val ui by vm.ui.collectAsState()
    val pending by vm.pending.collectAsState()
    val uploaded by vm.uploaded.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }

    run {
        Text(
            "Tujuan unggah data primer. Ubah hanya bila proyek Supabase dipindah.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted,
        )

        Section(title = "Sinkronisasi data primer") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Stat(Icons.Outlined.CloudUpload, "$pending", "menunggu unggah", if (pending > 0) Palette.Warning else Palette.TextMuted)
                Stat(Icons.Outlined.CloudDone, "$uploaded", "sudah terunggah", Palette.Accent)
            }
            if (pending > 0 && ui.config.enabled) SecondaryButton("Unggah sekarang", vm::syncNow, icon = Icons.Outlined.CloudUpload)
        }

        Section(
            title = "Proyek",
            action = { if (!editing) TextButton(onClick = { editing = true }) { Text("Ubah") } },
        ) {
            if (!editing) {
                KeyValue("URL", ui.config.url.removePrefix("https://").ifBlank { "belum diisi" })
                KeyValue("Key", if (ui.config.publishableKey.isBlank()) "belum diisi" else BackendViewModel.mask(ui.config.publishableKey))
                KeyValue("Sumber", if (ui.isDefault) "bawaan aplikasi" else "diubah di ponsel ini")
            } else {
                EditForm(
                    initialUrl = ui.config.url,
                    currentKeyMasked = BackendViewModel.mask(ui.config.publishableKey),
                    error = ui.error,
                    onSave = { u, k -> if (vm.save(u, k)) editing = false },
                    onCancel = { vm.clearError(); editing = false },
                )
            }
            ui.savedMessage?.let { if (!editing) Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Accent) }
        }

        Section(title = "Uji koneksi") {
            if (ui.testing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    ContainedLoadingIndicator(Modifier.size(40.dp))
                    Text("Menghubungi Supabase…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            ui.checks.forEachIndexed { i, c ->
                if (i > 0) Hairline()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    PlainIcon(
                        if (c.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                        if (c.ok) Palette.Accent else Palette.Danger, 22.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    }
                }
            }
            if (!ui.testing && ui.checks.isEmpty()) {
                Text("Memeriksa sesi anonim, tabel captures, dan bucket fish-eyes.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
            PrimaryButton("Uji koneksi", vm::test, icon = Icons.Outlined.NetworkCheck, enabled = !ui.testing)
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
            Notice("Hanya publishable atau anon key. Secret key ditolak karena memberi akses penuh ke database.", warning = false)
            SecondaryButton("Kembalikan default", vm::reset, icon = Icons.Outlined.Restore, enabled = !ui.isDefault)
        }
    }
}

@Composable
private fun EditForm(
    initialUrl: String,
    currentKeyMasked: String,
    error: String?,
    onSave: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var key by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("URL proyek") },
        placeholder = { Text("https://xxxx.supabase.co") }, singleLine = true)
    OutlinedTextField(
        key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Publishable key") },
        supportingText = { Text("Kosongkan untuk tetap memakai $currentKeyMasked") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
    )
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Danger) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
        PrimaryButton("Simpan", { onSave(url, key) }, icon = Icons.Outlined.Edit)
        SecondaryButton("Batal", onCancel)
    }
}

@Composable
private fun RowScope.Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, tint: Color) = Column(
    Modifier.weight(1f).clip(MaterialTheme.shapes.medium).background(Color.White)
        .border(1.dp, Palette.Divider, MaterialTheme.shapes.medium).padding(Space.m),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    PlainIcon(icon, tint, 22.dp)
    Text(value, style = MaterialTheme.typography.headlineMedium)
    Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
}
