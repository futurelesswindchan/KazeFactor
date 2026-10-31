package io.github.futurelesswindchan.kazefactor.ui.screen

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeCodeUiState
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeCodeViewModel
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 配方口令码界面
 * 
 * 支持两种模式：
 * 1. 导出模式（recipeId != null）：生成并展示口令码
 * 2. 导入模式（recipeId == null）：粘贴口令码并导入
 * 
 * @param viewModel 口令码 ViewModel
 * @param recipeId 配方 ID（null 表示导入模式）
 * @param onNavigateBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeCodeScreen(
    viewModel: RecipeCodeViewModel,
    recipeId: String?,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // 导出模式：自动生成口令码
    LaunchedEffect(recipeId) {
        if (recipeId != null) {
            viewModel.generateCode(recipeId)
        }
    }
    
    // 导入成功：自动返回
    LaunchedEffect(uiState) {
        if (uiState is RecipeCodeUiState.ImportSuccess) {
            delay(1500) // 显示成功提示 1.5 秒
            onNavigateBack()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (recipeId != null) "导出口令码" else "导入口令码"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val state = uiState) {
                RecipeCodeUiState.Idle -> {
                    if (recipeId == null) {
                        // 导入模式：输入界面
                        ImportCodeContent(viewModel = viewModel)
                    }
                }
                RecipeCodeUiState.Loading -> {
                    LoadingContent()
                }
                is RecipeCodeUiState.CodeGenerated -> {
                    // 导出模式：显示口令码
                    ExportCodeContent(
                        code = state.code,
                        recipeName = state.recipeName
                    )
                }
                is RecipeCodeUiState.ImportSuccess -> {
                    // 导入成功
                    SuccessContent(recipeName = state.recipeName)
                }
                is RecipeCodeUiState.Error -> {
                    // 错误提示
                    ErrorContent(
                        message = state.message,
                        onRetry = { viewModel.resetState() }
                    )
                }
            }
        }
    }
}

/**
 * 导入模式：输入界面
 */
@Composable
private fun ImportCodeContent(viewModel: RecipeCodeViewModel) {
    var codeInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 说明文字
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                text = "📋 粘贴从其他设备分享的口令码，快速导入配方",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(12.dp)
            )
        }
        
        // 口令码输入
        OutlinedTextField(
            value = codeInput,
            onValueChange = { codeInput = it },
            label = { Text("口令码") },
            placeholder = { Text("SKF1-XXXXXXXX-XX") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = false,
            minLines = 3
        )
        
        // 配方名输入
        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("配方名") },
            placeholder = { Text("给导入的配方起个名字") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        
        // 导入按钮
        Button(
            onClick = {
                viewModel.importCode(
                    code = codeInput.trim(),
                    recipeName = nameInput.trim()
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            enabled = codeInput.isNotBlank() && nameInput.isNotBlank()
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "导入",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("导入配方")
        }
    }
}

/**
 * 导出模式：显示口令码
 */
@Composable
private fun ExportCodeContent(
    code: String,
    recipeName: String
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showCopiedHint by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 说明文字
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                text = "🔗 复制下方口令码分享给好友，或在其他设备导入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(12.dp)
            )
        }
        
        // 配方名
        Text(
            text = "配方：$recipeName",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        // 口令码卡片
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "口令码",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.textTertiary
                )
                
                SelectionContainer {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 复制按钮
            Button(
                onClick = {
                    // LocalClipboard 的写入是 suspend 函数，需在协程中调用
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("KazeFactor 口令码", code))
                        )
                        showCopiedHint = true
                        delay(2000)
                        showCopiedHint = false
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (showCopiedHint) "已复制" else "复制口令码")
            }
            
            // 分享按钮（TODO: 实现系统分享）
            OutlinedButton(
                onClick = { /* TODO: 系统分享 */ },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "分享",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("分享")
            }
        }
    }
}

/**
 * 加载中
 */
@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * 导入成功
 */
@Composable
private fun SuccessContent(recipeName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "✅",
            style = MaterialTheme.typography.displayLarge
        )
        Text(
            text = "导入成功",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "配方「$recipeName」已导入",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.textTertiary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 错误提示
 */
@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "❌ 错误",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text("重试")
        }
    }
}
