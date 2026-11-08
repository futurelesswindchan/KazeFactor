package io.github.futurelesswindchan.kazefactor.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.futurelesswindchan.kazefactor.OverlayController
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeListViewModel
import io.github.futurelesswindchan.kazefactor.service.KazeAccessibilityService
import io.github.futurelesswindchan.kazefactor.ui.component.CompactFactorChip
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelectionState
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary

/**
 * 配方列表界面（主界面，产品规格 2026-11-08）。
 * 
 * 重点优化：
 * 1. TopAppBar 标题精简单行，避免右侧按钮挤压导致断行（修复 `05.jpg`）；
 * 2. 列表顶部置顶「⚡ 自动刷新状态与启动横幅」+ 3 步可折叠向导，让新手一眼知道如何去游戏里刷；
 * 3. 配方卡片展示因子中文名称标签，消除盲盒感。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(
    viewModel: RecipeListViewModel,
    onNavigateToEditor: (String?) -> Unit,
    onNavigateToRecipeCode: (String?) -> Unit,
    onNavigateToPermissionGuide: () -> Unit = {}
) {
    val recipes by viewModel.recipes.collectAsState()
    val isServiceConnected by KazeAccessibilityService.isConnected.collectAsState()
    val isOverlayShowing by OverlayController.isOverlayShowing.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "KazeFactor",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                actions = {
                    TextButton(onClick = { onNavigateToRecipeCode(null) }) {
                        Text("导入口令", maxLines = 1)
                    }
                    TextButton(onClick = onNavigateToPermissionGuide) {
                        Text("权限设置", maxLines = 1)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigateToEditor(null) },
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建配方")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶部置顶：「⚡ 自动刷新三态状态横幅 + 3 步使用向导」
            item(key = "auto_refresh_banner") {
                AutoRefreshLaunchBanner(
                    isServiceConnected = isServiceConnected,
                    isOverlayShowing = isOverlayShowing,
                    onNavigateToPermission = onNavigateToPermissionGuide,
                    onToggleOverlay = { OverlayController.toggle() }
                )
            }

            if (recipes.isEmpty()) {
                item(key = "empty_state") {
                    EmptyState(
                        onCreateRecipe = { onNavigateToEditor(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                    )
                }
            } else {
                items(
                    items = recipes,
                    key = { it.id }
                ) { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        viewModel = viewModel,
                        onEdit = { onNavigateToEditor(recipe.id) },
                        onShare = { onNavigateToRecipeCode(recipe.id) },
                        onDelete = { viewModel.deleteRecipe(recipe.id) }
                    )
                }
            }
        }
    }
}

/**
 * 自动刷新启动引导横幅（置顶三态卡片）
 */
@Composable
private fun AutoRefreshLaunchBanner(
    isServiceConnected: Boolean,
    isOverlayShowing: Boolean,
    onNavigateToPermission: () -> Unit,
    onToggleOverlay: () -> Unit
) {
    var isTutorialExpanded by remember { mutableStateOf(!isServiceConnected) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !isServiceConnected -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                isOverlayShowing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 状态标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        !isServiceConnected -> "自动刷新服务未开启"
                        isOverlayShowing -> "悬浮球运行中"
                        else -> "自动刷新已就绪"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        !isServiceConnected -> MaterialTheme.colorScheme.error
                        isOverlayShowing -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )

                Text(
                    text = if (isTutorialExpanded) "收起指南 ▴" else "使用指南 ▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isTutorialExpanded = !isTutorialExpanded }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Text(
                text = when {
                    !isServiceConnected -> "开启无障碍服务后，即可在游戏内呼出悬浮球自动刷因子"
                    isOverlayShowing -> "切入《元气骑士》联机模式挑战因子界面，点击屏幕边缘悬浮球即可开刷"
                    else -> "悬浮球待命中，点击下方按钮呼出悬浮球，即可切入游戏配置界面开刷"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )

            // 可折叠 3 步使用向导
            AnimatedVisibility(visible = isTutorialExpanded) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "3 步极简使用步骤：",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "1. 点击下方按钮开启悬浮窗（屏幕会出现小圆球）；\n" +
                                "2. 进入《元气骑士》联机建房界面 → 打开「挑战因子」开关，确保显示四个因子而不是问号；\n" +
                                "3. 点击悬浮球展开面板，选好配方点「开始自动刷新」即可！",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.textTertiary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // 快捷操作大按钮
            if (!isServiceConnected) {
                Button(
                    onClick = onNavigateToPermission,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("前往开启无障碍服务", fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onToggleOverlay,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isOverlayShowing) "收起悬浮窗" else "开启悬浮窗模式",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = onNavigateToPermission,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("权限详情", maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * 空状态提示
 */
@Composable
private fun EmptyState(
    onCreateRecipe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有配方",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "点击下方按钮创建第一个挑战因子配方吧",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.textTertiary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onCreateRecipe,
            shape = RoundedCornerShape(999.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "新建",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("新建配方")
        }
    }
}

/**
 * 配方卡片（带因子图标与中文名称）
 */
@Composable
private fun RecipeCard(
    recipe: Recipe,
    viewModel: RecipeListViewModel,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val probability = remember(recipe) { viewModel.getRecipeProbability(recipe) }
    val summary = remember(recipe) { viewModel.getRecipeSummary(recipe) }
    val previewFactors = remember(recipe) {
        val wanted = recipe.wantedFactorIds.mapNotNull { id ->
            viewModel.getFactorById(id)?.let { it to FactorSelectionState.WANTED }
        }
        val unwanted = recipe.unwantedFactorIds.mapNotNull { id ->
            viewModel.getFactorById(id)?.let { it to FactorSelectionState.UNWANTED }
        }
        wanted + unwanted
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 标题栏（名称 + 概率）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                if (probability > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${"%.2f".format(probability * 100)}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            
            // 简要信息
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.textTertiary
            )

            // 配方内已选（想要）与排除（不想要）的因子图标预览（仅纯图标与绿/红框，无勾叉遮挡）
            if (previewFactors.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(
                        items = previewFactors,
                        key = { it.first.id }
                    ) { (factor, state) ->
                        CompactFactorChip(
                            factor = factor,
                            selectionState = state,
                            size = 38.dp
                        )
                    }
                }
            }
            
            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "分享",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    
    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除配方「${recipe.name}」吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
