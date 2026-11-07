package io.github.futurelesswindchan.kazefactor.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeListViewModel
import io.github.futurelesswindchan.kazefactor.ui.component.A11yDebugDialog
import io.github.futurelesswindchan.kazefactor.ui.component.CompactFactorChip
import io.github.futurelesswindchan.kazefactor.ui.component.FactorSelectionState
import io.github.futurelesswindchan.kazefactor.ui.theme.textTertiary

/**
 * 配方列表界面（主界面）
 * 
 * @param viewModel 配方列表 ViewModel
 * @param onNavigateToEditor 跳转到编辑界面（参数：配方 ID，null 表示新建）
 * @param onNavigateToRecipeCode 跳转到口令码界面（参数：配方 ID，null 表示导入模式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(
    viewModel: RecipeListViewModel,
    onNavigateToEditor: (String?) -> Unit,
    onNavigateToRecipeCode: (String?) -> Unit
) {
    val recipes by viewModel.recipes.collectAsState()
    var showDebugDialog by remember { mutableStateOf(false) }

    if (showDebugDialog) {
        A11yDebugDialog(onDismiss = { showDebugDialog = false })
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KazeFactor - 配方管理") },
                actions = {
                    // TODO(第 6 步): 临时调试入口，第 6 步替换为正式悬浮窗与权限引导页
                    TextButton(onClick = { showDebugDialog = true }) {
                        Text("调试")
                    }
                    TextButton(onClick = { onNavigateToRecipeCode(null) }) {
                        Text("导入口令")
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
        if (recipes.isEmpty()) {
            // 空状态
            EmptyState(
                onCreateRecipe = { onNavigateToEditor(null) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            // 配方列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
        // 使用 Spacer 实现垂直居中
        Spacer(modifier = Modifier.weight(1f))
        
        Text(
            text = "📦",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "还没有配方",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "点击下方按钮创建第一个配方吧",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.textTertiary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
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
        
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * 配方卡片
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题栏（名称 + 概率）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.textTertiary
            )

            // 配方内已选（想要）与排除（不想要）的因子图标预览
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
                            size = 42.dp
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
