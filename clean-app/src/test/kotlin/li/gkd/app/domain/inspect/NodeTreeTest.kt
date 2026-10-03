package li.gkd.app.domain.inspect

import li.gkd.app.data.AttrInfo
import li.gkd.app.data.NodeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `NodeTree.flattenVisible` 的行为测试。
 *
 * 为什么值得测：审查页显示哪些行、层级对不对，完全由这里决定。若「收起时子树
 * 未整体隐藏」或「子节点顺序错乱」，排查规则的人会看到一棵**错误的树**，
 * 并据此写出错误的选择器 —— 错误不会报错，只会静默误导。
 */
class NodeTreeTest {

    /** AttrInfo 没有默认值，测试里只关心层级，其余字段用占位值。 */
    private fun node(
        id: Int,
        pid: Int,
        depth: Int,
        childCount: Int = 0,
        name: String = "View",
    ): NodeInfo = NodeInfo(
        id = id,
        pid = pid,
        idQf = null,
        textQf = null,
        attr = AttrInfo(
            id = null,
            vid = null,
            name = name,
            text = null,
            desc = null,
            clickable = false,
            focusable = false,
            checkable = false,
            checked = null,
            editable = false,
            longClickable = false,
            visibleToUser = true,
            left = 0,
            top = 0,
            right = 10,
            bottom = 10,
            width = 10,
            height = 10,
            childCount = childCount,
            index = 0,
            depth = depth,
        ),
    )

    /**
     * 树：0 ─ 1 ─ 3
     *      └ 2
     */
    private val tree = listOf(
        node(0, -1, 0, childCount = 2),
        node(1, 0, 1, childCount = 1),
        node(2, 0, 1),
        node(3, 1, 2),
    )

    private fun ids(nodes: List<NodeInfo>): List<Int> = nodes.map { it.id }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertEquals(emptyList<Int>(), ids(NodeTree.flattenVisible(emptyList(), emptySet())))
    }

    @Test
    fun collapsedRootHidesWholeSubtree() {
        assertEquals(listOf(0), ids(NodeTree.flattenVisible(tree, emptySet())))
    }

    @Test
    fun expandedRootRevealsChildrenInPreOrder() {
        // 前序：0, 1, 2 —— 3 仍被收起的 1 挡住
        assertEquals(listOf(0, 1, 2), ids(NodeTree.flattenVisible(tree, setOf(0))))
    }

    @Test
    fun expandingChildRevealsGrandchildInPlace() {
        // 3 必须紧跟在父节点 1 之后，而不是被排到末尾
        assertEquals(listOf(0, 1, 3, 2), ids(NodeTree.flattenVisible(tree, setOf(0, 1))))
    }

    @Test
    fun expandedIdThatIsNotInTreeIsIgnored() {
        assertEquals(listOf(0, 1, 2), ids(NodeTree.flattenVisible(tree, setOf(0, 999))))
    }

    @Test
    fun deepChainDoesNotOverflowStack() {
        // 无障碍节点树可以很深；实现用显式栈，这里锁住该性质。
        val depth = 3000
        val chain = (0 until depth).map { i ->
            node(i, if (i == 0) -1 else i - 1, i, childCount = if (i == depth - 1) 0 else 1)
        }
        val visible = NodeTree.flattenVisible(chain, chain.mapTo(mutableSetOf()) { it.id })
        assertEquals(depth, visible.size)
        assertEquals((0 until depth).toList(), ids(visible))
    }

    @Test
    fun collapsingMiddleOfChainHidesEverythingBelow() {
        val depth = 50
        val chain = (0 until depth).map { i ->
            node(i, if (i == 0) -1 else i - 1, i, childCount = if (i == depth - 1) 0 else 1)
        }
        // 展开到 9，其后不再展开：可见的是 0..10（第 10 个节点自身可见，其子节点不可见）
        assertEquals((0..10).toList(), ids(NodeTree.flattenVisible(chain, (0..9).toSet())))
    }
}
