package io.github.futurelesswindchan.kazefactor.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeEditorViewModel
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelectionState
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelector
import io.github.futurelesswindchan.kazefactor.ui.component.RuleViolationCard
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary
import kotlinx.coroutines.launch

/**
 * 配方编辑界面
 * 
 * @param viewModel 配方编辑 ViewModel
 * @param onNavigateBack 返回回调
 * @param onNavigateToRecipeCode 跳转到口令码界面（传入配方）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(
    viewModel: RecipeEditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToRecipeCode: (String) -> Unit = {}
) {
    val recipeName by viewModel.recipeName.collectAsState()
    val factorSelections by viewModel.factorSelections.collectAsState()
    val minOptional by viewModel.minOptional.collectAsState()
    val violations by viewModel.violations.collectAsState()
    val hasBlockingErrors by viewModel.hasBlockingErrors.collectAsState()
    val probability by viewModel.probability.collectAsState()
    val currentRecipe by viewModel.currentRecipe.collectAsState()
    
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentRecipe.id.isEmpty()) "新建配方" else "编辑配方") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 分享按钮（生成口令码）
                    IconButton(
                        onClick = { onNavigateToRecipeCode(currentRecipe.id) },
                        enabled = !hasBlockingErrors
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                    
                    // 保存按钮
                    IconButton(
                        onClick = {
                            scope.launch {
                                viewModel.saveRecipe().onSuccess {
                                    snackbarHostState.showSnackbar("保存成功")
                                    onNavigateBack()
                                }.onFailure {
                                    snackbarHostState.showSnackbar("保存失败：${it.message}")
                                }
                            }
                        },
                        enabled = !hasBlockingErrors && recipeName.isNotBlank()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "保存")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 配方名称输入
            OutlinedTextField(
                value = recipeName,
                onValueChange = { viewModel.updateRecipeName(it) },
                label = { Text("配方名称") },
                placeholder = { Text("例如：超简单因子、刷钱因子") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            
            // 规则违规提示（错误 + 警告）
            if (violations.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    violations.forEach { violation ->
                        RuleViolationCard(
                            violation = violation,
                            onAutoFix = { viewModel.autoFixViolation(it) }
                        )
                    }
                }
            }
            
            // 概率显示
            ProbabilityCard(probability = probability)
            
            // 可选因子最小数量调整（仅在有可选因子时显示）
            val optionalCount = factorSelections.count { it.value == FactorSelectionState.OPTIONAL }
            if (optionalCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "可选因子最小数量：$minOptional 个",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Slider(
                            value = minOptional.toFloat(),
                            onValueChange = { viewModel.updateMinOptional(it.toInt()) },
                            valueRange = 0f..optionalCount.toFloat(),
                            steps = optionalCount - 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "从 $optionalCount 个可选因子中，至少要出现 $minOptional 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.textTertiary
                        )
                    }
                }
            }
            
            // 因子选择器
            Text(
                text = "选择因子",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "点击因子切换状态：想要（✓）→ 不想要（✗）→ 可选（★）→ 未选中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textTertiary
            )
            
            FactorSelector(
                factorsByCategory = viewModel.getFactorsByCategory(),
                selectionStates = factorSelections,
                onFactorClick = { factorId, _ ->
                    viewModel.toggleFactorSelection(factorId)
                },
                modifier = Modifier.heightIn(max = 400.dp)
            )
        }
    }
}

/**
 * 概率显示卡片
 */
@Composable
private fun ProbabilityCard(probability: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "命中概率",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            if (probability > 0.0) {
                Text(
                    text = "${"%.4f".format(probability * 100)}%",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val expectedRefreshCount = (1.0 / probability).toLong()
                Text(
                    text = "预计刷约 $expectedRefreshCount 次",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textTertiary,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.textTertiary
                )
                Text(
                    text = "请先选择想要的因子",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.textTertiary
                )
            }
        }
    }
}
