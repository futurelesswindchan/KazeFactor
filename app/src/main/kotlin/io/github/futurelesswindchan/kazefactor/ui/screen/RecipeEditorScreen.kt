package io.github.futurelesswindchan.kazefactor.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.domain.model.Factor
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeEditorViewModel
import io.github.futurelesswindchan.kazefactor.ui.component.CompactFactorChip
import io.github.futurelesswindchan.kazefactor.ui.component.FactorDisplayMode
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelectionState
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelector
import io.github.futurelesswindchan.kazefactor.ui.component.RuleViolationCard
import io.github.futurelesswindchan.kazefactor.ui.theme.surface3
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary
import io.github.futurelesswindchan.kazefactor.ui.theme.warning
import kotlinx.coroutines.launch

/**
 * 配方编辑界面（支持列表与紧凑图标网格双模式切换 + 置顶已选/排除预览栏）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(
    viewModel: RecipeEditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToRecipeCode: (String) -> Unit = {}
) {
    val recipeName by viewModel.recipeName.collectAsState()
    val factorSelections by viewModel.factorSelections.collectAsState()
    val violations by viewModel.violations.collectAsState()
    val hasBlockingErrors by viewModel.hasBlockingErrors.collectAsState()
    val probability by viewModel.probability.collectAsState()
    val currentRecipe by viewModel.currentRecipe.collectAsState()

    var displayMode by rememberSaveable { mutableStateOf(FactorDisplayMode.GRID) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // 防止连点保存：两次保存会写入两条新配方，并触发两次返回
    var isSaving by remember { mutableStateOf(false) }

    // Compose 不会因为键盘收起而清除焦点，这里在键盘由显示变为隐藏时手动清除，
    // 否则输入框会一直保留光标
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) focusManager.clearFocus()
    }

    val factorsByCategory = remember { viewModel.getFactorsByCategory() }
    val allFactorsMap = remember(factorsByCategory) {
        factorsByCategory.values.flatten().associateBy { it.id }
    }

    // 汇总已选（想要）和排除（不想要）的因子，想要排在前面，排除排在后面
    val pinnedSelectedFactors = remember(factorSelections, allFactorsMap) {
        val wanted = factorSelections.entries
            .filter { it.value == FactorSelectionState.WANTED }
            .mapNotNull { entry -> allFactorsMap[entry.key]?.let { it to entry.value } }
        val unwanted = factorSelections.entries
            .filter { it.value == FactorSelectionState.UNWANTED }
            .mapNotNull { entry -> allFactorsMap[entry.key]?.let { it to entry.value } }
        wanted + unwanted
    }

    // 过滤非阻塞性警告（低概率），这些将整合到 InfoCard 中
    val blockingViolations = violations.filter { it.isBlocking }
    val warningViolations = violations.filter { !it.isBlocking }

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
                    IconButton(
                        onClick = { onNavigateToRecipeCode(currentRecipe.id) },
                        enabled = !hasBlockingErrors && currentRecipe.id.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
                    }
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            isSaving = true
                            scope.launch {
                                viewModel.saveRecipe().onSuccess {
                                    // 用 Toast 而非 Snackbar：Snackbar 属于本页面，返回后即被销毁；
                                    // 且 showSnackbar 会挂起到提示消失（约 4 秒）才继续执行返回
                                    Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
                                    onNavigateBack()
                                }.onFailure {
                                    isSaving = false
                                    snackbarHostState.showSnackbar("保存失败：${it.message}")
                                }
                            }
                        },
                        enabled = !isSaving && !hasBlockingErrors && recipeName.isNotBlank()
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
                // 点击输入框以外的空白区域时收起键盘并清除焦点
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 配方名称输入
            OutlinedTextField(
                value = recipeName,
                onValueChange = { viewModel.updateRecipeName(it) },
                label = { Text("配方名称") },
                placeholder = { Text("例如：超简单因子、刷钱因子") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(16.dp)
            )

            // 整合型信息栏（常驻固定高度：命中概率 + 置顶已选/排除因子槽）
            InfoCard(
                probability = probability,
                hasWarnings = warningViolations.isNotEmpty(),
                pinnedFactors = pinnedSelectedFactors,
                onPinnedFactorClick = { factorId ->
                    focusManager.clearFocus()
                    viewModel.toggleFactorSelection(factorId)
                }
            )

            // 只有真正阻塞的错误才单独显示（它们确实需要修复，必须显示）
            if (blockingViolations.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    blockingViolations.forEach { violation ->
                        RuleViolationCard(
                            violation = violation,
                            onAutoFix = { viewModel.autoFixViolation(it) }
                        )
                    }
                }
            }

            // 因子选择区标题 + 模式切换滑钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "选择因子",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "点击切换：想要(绿框) → 不要(红框) → 默认",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.textTertiary
                    )
                }

                DisplayModeToggle(
                    currentMode = displayMode,
                    onModeChange = { displayMode = it }
                )
            }

            FactorSelector(
                factorsByCategory = factorsByCategory,
                selectionStates = factorSelections,
                onFactorClick = { factorId, _ ->
                    // 因子图标自身会消费点击事件，空白区域的点击监听收不到，需单独清除焦点
                    focusManager.clearFocus()
                    viewModel.toggleFactorSelection(factorId)
                },
                displayMode = displayMode,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 紧凑网格 / 列表模式切换滑钮
 */
@Composable
private fun DisplayModeToggle(
    currentMode: FactorDisplayMode,
    onModeChange: (FactorDisplayMode) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface3)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModePillItem(
            selected = currentMode == FactorDisplayMode.GRID,
            icon = Icons.Default.GridView,
            label = "图标",
            onClick = { onModeChange(FactorDisplayMode.GRID) }
        )
        ModePillItem(
            selected = currentMode == FactorDisplayMode.LIST,
            icon = Icons.AutoMirrored.Filled.ViewList,
            label = "列表",
            onClick = { onModeChange(FactorDisplayMode.LIST) }
        )
    }
}

@Composable
private fun ModePillItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}

/**
 * 整合型信息栏（命中概率 + 预计次数 + 警告标识 + 置顶已选/排除因子横向栏）
 */
@Composable
private fun InfoCard(
    probability: Double,
    hasWarnings: Boolean,
    pinnedFactors: List<Pair<Factor, FactorSelectionState>>,
    onPinnedFactorClick: (Int) -> Unit
) {
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 上半部分：命中概率 + 预计次数 / 低概率警告
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "命中概率",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = if (probability > 0.0) "${"%.4f".format(probability * 100)}%" else "—",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (probability > 0.0) {
                        val expectedRefreshCount = (1.0 / probability).toLong().coerceAtLeast(1L)
                        Text(
                            text = "预计刷约 $expectedRefreshCount 次",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Text(
                            text = "请选择想要的因子",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }

                    if (hasWarnings) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "警告",
                                tint = MaterialTheme.colorScheme.warning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "命中概率较低",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
            )

            // 下半部分：常驻固定高度的「已选与排除」置顶预览槽（绝不引起界面跳动）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (pinnedFactors.isEmpty()) {
                    Text(
                        text = "暂未选择因子（在下方点选后将置顶汇总于此）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(
                            items = pinnedFactors,
                            key = { it.first.id }
                        ) { (factor, state) ->
                            CompactFactorChip(
                                factor = factor,
                                selectionState = state,
                                size = 44.dp,
                                onClick = { onPinnedFactorClick(factor.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
