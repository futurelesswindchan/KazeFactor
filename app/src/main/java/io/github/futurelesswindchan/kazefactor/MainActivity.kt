package io.github.futurelesswindchan.kazefactor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.futurelesswindchan.kazefactor.data.repository.RecipeRepository
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeCodeViewModel
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeEditorViewModel
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.RecipeListViewModel
import io.github.futurelesswindchan.kazefactor.domain.viewmodel.ViewModelFactory
import io.github.futurelesswindchan.kazefactor.ui.navigation.NavigationRoutes
import io.github.futurelesswindchan.kazefactor.ui.screen.PermissionGuideScreen
import io.github.futurelesswindchan.kazefactor.ui.screen.RecipeCodeScreen
import io.github.futurelesswindchan.kazefactor.ui.screen.RecipeEditorScreen
import io.github.futurelesswindchan.kazefactor.ui.screen.RecipeListScreen
import io.github.futurelesswindchan.kazefactor.ui.theme.KazeFactorTheme

/**
 * KazeFactor 主界面
 * 
 * 职责：
 * 1. 初始化依赖（从 Application 获取 Repository）
 * 2. 设置导航系统（NavHost + 三个界面）
 * 3. 应用天蓝色主题
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 从全局 Application 获取 Repository
        val repository = (application as KazeFactorApplication).recipeRepository
        
        setContent {
            KazeFactorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KazeFactorApp(repository = repository)
                }
            }
        }
    }
}

/**
 * KazeFactor 应用主体（导航系统）
 * 
 * @param repository 配方数据仓库（从 Application 注入）
 * @param modifier Compose 修饰符
 */
@Composable
fun KazeFactorApp(
    repository: RecipeRepository,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val database = (LocalContext.current.applicationContext as KazeFactorApplication).factorDatabase
    val viewModelFactory = ViewModelFactory(repository, database)
    
    NavHost(
        navController = navController,
        startDestination = NavigationRoutes.RECIPE_LIST,
        modifier = modifier
    ) {
        // 配方列表界面（主界面）
        composable(NavigationRoutes.RECIPE_LIST) {
            val viewModel: RecipeListViewModel = viewModel(factory = viewModelFactory)
            RecipeListScreen(
                viewModel = viewModel,
                onNavigateToEditor = { recipeId ->
                    navController.navigate(NavigationRoutes.buildRecipeEditorRoute(recipeId))
                },
                onNavigateToRecipeCode = { recipeId ->
                    navController.navigate(NavigationRoutes.buildRecipeCodeRoute(recipeId))
                },
                onNavigateToPermissionGuide = {
                    navController.navigate(NavigationRoutes.PERMISSION_GUIDE)
                }
            )
        }

        // 自动刷新权限引导与悬浮窗开关界面
        composable(NavigationRoutes.PERMISSION_GUIDE) { backStackEntry ->
            PermissionGuideScreen(
                onNavigateBack = {
                    navController.popBackStackFrom(backStackEntry)
                }
            )
        }
        
        // 配方编辑界面
        composable(
            route = NavigationRoutes.RECIPE_EDITOR_ROUTE,
            arguments = listOf(
                navArgument("recipeId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val recipeId = backStackEntry.arguments?.getString("recipeId")
            
            // 使用专用的 ViewModelFactory 传入 recipeId
            val viewModel: RecipeEditorViewModel = viewModel(
                factory = ViewModelFactory(repository, database, recipeId)
            )
            
            RecipeEditorScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStackFrom(backStackEntry)
                },
                onNavigateToRecipeCode = { savedRecipeId ->
                    navController.navigate(NavigationRoutes.buildRecipeCodeRoute(savedRecipeId))
                }
            )
        }
        
        // 口令码界面
        composable(
            route = NavigationRoutes.RECIPE_CODE_ROUTE,
            arguments = listOf(
                navArgument("recipeId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val recipeId = backStackEntry.arguments?.getString("recipeId")
            val viewModel: RecipeCodeViewModel = viewModel(factory = viewModelFactory)
            
            RecipeCodeScreen(
                viewModel = viewModel,
                recipeId = recipeId,
                onNavigateBack = {
                    navController.popBackStackFrom(backStackEntry)
                }
            )
        }
    }
}

/**
 * 仅当 [entry] 仍是当前活跃页面时才返回上一页。
 *
 * 同一页面可能触发两次返回（例如保存后的自动返回与用户点击返回键重叠，
 * 或导入成功后的延时返回与手动返回重叠）。第二次 popBackStack 会把起始页
 * 配方列表也弹出，NavHost 变为空白，表现为白屏卡死。页面开始退出后其
 * lifecycle 不再是 RESUMED，借此过滤掉重复的返回请求。
 */
private fun NavHostController.popBackStackFrom(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
        popBackStack()
    }
}
