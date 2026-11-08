package com.xjyzs.operator.utils.execute

object AgentTools {
    private val num = mapOf("type" to "number")
    private val str = mapOf("type" to "string")

    private fun fn(name: String, desc: String, props: Map<String, Any> = emptyMap()) = mapOf(
        "type" to "function", "function" to mapOf(
            "name" to name, "description" to desc, "parameters" to mapOf(
                "type" to "object", "properties" to props, "required" to props.keys.toList()
            )
        )
    )

    val all = listOf(
        fn("tap", "点击", mapOf("x" to num, "y" to num, "intent" to str)),
        fn(
            "swipe",
            "从起点滑动到终点",
            mapOf("x1" to num, "y1" to num, "x2" to num, "y2" to num, "intent" to str)
        ),
        fn(
            "tap_and_type",
            "清空指定坐标的文本框并输入文字",
            mapOf("x" to num, "y" to num, "text" to str)
        ),
        fn("type", "清空当前输入框并输入文字", mapOf("text" to str)),
        fn("enter", "按执行键(即回车/搜索/发送等)"),
        fn("launch", "启动应用", mapOf("app" to str)),
        fn("back", "返回", mapOf("intent" to str)),
        fn("wait", "等待", mapOf("seconds" to num, "intent" to str)),
        fn("long_press", "长按", mapOf("x" to num, "y" to num, "intent" to str)),
        fn("double_tap", "双击", mapOf("x" to num, "y" to num, "intent" to str)),
        fn("take_over", "遇到登录、验证码、支付等需要用户接管时调用", mapOf("reason" to str)),
        fn("finish", "任务完成时调用", mapOf("message" to str)),
    )
}