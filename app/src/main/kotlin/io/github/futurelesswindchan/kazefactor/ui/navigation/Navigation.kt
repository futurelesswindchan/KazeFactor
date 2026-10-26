package io.github.futurelesswindchan.kazefactor.ui.navigation

/**
 * 导航路由定义
 * 
 * 职责：
 * 1. 定义所有界面的路由路径
 * 2. 封装路由参数的构建逻辑
 */
object NavigationRoutes {
    /**
     * 配方列表界面（主界面）
     */
    const val RECIPE_LIST = "recipe_list"
    
    /**
     * 配方编辑界面
     * 参数：recipeId（可选，null 表示新建）
     */
    const val RECIPE_EDITOR = "recipe_editor"
    
    /**
     * 配方编辑界面完整路由（带参数占位符）
     */
    const val RECIPE_EDITOR_ROUTE = "$RECIPE_EDITOR?recipeId={recipeId}"
    
    /**
     * 口令码界面
     * 参数：recipeId（可选，null 表示导入模式，非 null 表示导出模式）
     */
    const val RECIPE_CODE = "recipe_code"
    
    /**
     * 口令码界面完整路由（带参数占位符）
     */
    const val RECIPE_CODE_ROUTE = "$RECIPE_CODE?recipeId={recipeId}"
    
    /**
     * 构建配方编辑界面路由（用于导航跳转）
     * 
     * @param recipeId 配方 ID，null 表示新建
     * @return 完整路由字符串
     */
    fun buildRecipeEditorRoute(recipeId: String?): String {
        return if (recipeId != null) {
            "$RECIPE_EDITOR?recipeId=$recipeId"
        } else {
            RECIPE_EDITOR
        }
    }
    
    /**
     * 构建口令码界面路由（用于导航跳转）
     * 
     * @param recipeId 配方 ID，null 表示导入模式
     * @return 完整路由字符串
     */
    fun buildRecipeCodeRoute(recipeId: String? = null): String {
        return if (recipeId != null) {
            "$RECIPE_CODE?recipeId=$recipeId"
        } else {
            RECIPE_CODE
        }
    }
}
