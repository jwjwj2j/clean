package li.gkd.app.domain.inspect

import li.gkd.app.data.NodeInfo

/**
 * 节点树的纯逻辑：把 `info2nodeList` 产出的扁平列表按展开状态压成可见序列。
 *
 * 为什么单独放在领域层而不是页面里：它是**纯函数**，且决定了用户最终看到哪几行。
 * 「父在前、子紧随其后」「收起时子树整体隐藏」这两条一旦写错，
 * 审查页会静默显示错误的层级——排查规则时会被误导。因此它值得独立且可测。
 *
 * `info2nodeList` 返回的是**前序扁平**列表，用 `pid` 记录父节点（根为 -1），
 * 本函数据此重建层级。
 */
object NodeTree {
    /**
     * 按前序展开可见节点。
     *
     * 用显式栈而非递归：无障碍节点树可以很深（`info2nodeList` 允许到 5000 个节点），
     * 递归存在栈溢出风险。
     *
     * @param nodes   `info2nodeList` 的完整输出
     * @param expanded 处于展开状态的节点 id 集合
     */
    fun flattenVisible(nodes: List<NodeInfo>, expanded: Set<Int>): List<NodeInfo> {
        if (nodes.isEmpty()) return emptyList()
        val childrenOf = nodes.groupBy { it.pid }
        val output = ArrayList<NodeInfo>(nodes.size)
        val stack = ArrayDeque<NodeInfo>()
        // 逆序入栈，保证出栈顺序与原始前序一致
        childrenOf[-1].orEmpty().asReversed().forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            output.add(node)
            if (node.id in expanded) {
                childrenOf[node.id].orEmpty().asReversed().forEach { stack.addLast(it) }
            }
        }
        return output
    }
}
