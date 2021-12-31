package de.lostmekka.zls

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hexworks.zircon.api.CP437TilesetResources.rexPaint16x16
import org.hexworks.zircon.api.ColorThemes.arc
import org.hexworks.zircon.api.SwingApplications.startTileGrid
import org.hexworks.zircon.api.application.AppConfig
import org.hexworks.zircon.api.color.TileColor
import org.hexworks.zircon.api.data.Position
import org.hexworks.zircon.api.data.Tile
import org.hexworks.zircon.api.screen.Screen
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

const val width = 60
const val height = 30
fun main() {
    val tileGrid = startTileGrid(
        AppConfig.newBuilder()
            .withSize(width, height)
            .withDefaultTileset(rexPaint16x16())
            .build()
    )
    val screen = Screen.create(tileGrid)
    screen.display()
    screen.theme = arc()

    val updateJob = GlobalScope.launch {
        var lightning = createLightning()
        while (true) {
            repeat(15) { lightning.iterate() }
            lightning.drawTo(screen)
            if (lightning.state == Lightning.State.Done) lightning = createLightning()
            delay(50)
        }
    }
    screen.closedValue.onChange { if (it.newValue) updateJob.cancel() }
}

fun createLightning() = Lightning(Position.create(random(10..(width - 10)), 0), 0 until height, random(3..30))

class Lightning(startPos: Position, val yRange: IntRange, val randomness: Int) {
    companion object {
        const val singleLines = "   └ ─┘┴ ┌│├┐┬┤┼"
        const val doubleLines = "   ╚ ═╝╩ ╔║╠╗╦╣╬"
        const val right = 1
        const val up = 2
        const val left = 4
        const val down = 8
        val directions = listOf(right, up, left, down)
        fun oppositeOf(dir: Int) =
            when (dir) {
                right -> left
                up -> down
                left -> right
                down -> up
                else -> error("oops")
            }
    }

    private operator fun Int.contains(shape: Int) = shape and this > 0
    private fun Position.moved(directions: Int): Position {
        var x = x
        var y = y
        if (right in directions) x++
        if (left in directions) x--
        if (down in directions) y++
        if (up in directions) y--
        return Position.create(x, y)
    }

    inner class Node(
        val pos: Position,
        val originDirection: Int,
        val traveledDistance: Int,
        val parent: Node? = null,
    ) {
        var intensity = 0.7
        val totalCost = traveledDistance + yRange.last - pos.y + exp(Random.nextDouble() * randomness).roundToInt()
        var shape = originDirection
        val char
            get() = when {
                intensity > 1f -> doubleLines[shape]
                else -> singleLines[shape]
            }

        fun addDirection(dir: Int) {
            shape = shape or dir
        }

        fun path() = buildSet {
            var n = this@Node
            while (true) {
                add(n)
                n = n.parent ?: break
            }
        }
    }

    private val toExpand = PriorityQueue<Node>(Comparator.comparingInt { it.totalCost })
        .also { it += Node(startPos, up, 0) }
    private val nodes = mutableMapOf<Position, Node>()

    enum class State { Searching, Discharging, Fading, Done }

    var state = State.Searching
        private set
    private var stateCounter = 0
    private var finalPath = setOf<Node>()
    private var fadeAmount = 0.0

    fun iterate() {
        when (state) {
            State.Searching -> {
                val node = nextNodeToExpand()
                nodes[node.pos.moved(node.originDirection)]?.also { it.addDirection(oppositeOf(node.originDirection)) }
                nodes[node.pos] = node
                val currPath = node.path()
                if (node.pos.y > yRange.last) {
                    startDischarging(node, currPath)
                    return
                }
                for (direction in directions) {
                    val newPos = node.pos.moved(direction)
                    if (newPos in nodes) continue
                    if (newPos.y < yRange.first) continue
                    toExpand += Node(newPos, oppositeOf(direction), node.traveledDistance + 1, node)
                }
                redistributeIntensity(0.002, currPath)
            }
            State.Discharging -> {
                redistributeIntensity(0.01, finalPath)
                averageIntensity(finalPath)
                stateCounter--
                if (stateCounter == 0) startFading()
            }
            State.Fading -> {
                for (n in nodes.values) n.intensity -= fadeAmount
                stateCounter--
                if (stateCounter == 0) end()
            }
            State.Done -> return
        }
    }

    private fun startDischarging(
        node: Node,
        path: Set<Node>,
    ) {
        node.addDirection(down)
        finalPath = path
        state = State.Discharging
        stateCounter = random(40..80)
        averageIntensity(path)
        for (n in path) n.shape = 0
        var n = node
        n.addDirection(down)
        while (true) {
            val dir = n.originDirection
            n.addDirection(dir)
            n = n.parent ?: break
            n.addDirection(oppositeOf(dir))
        }
    }

    private fun startFading() {
        state = State.Fading
        stateCounter = random(40..80)
        fadeAmount = nodes.values.maxOf { it.intensity } / stateCounter
    }

    private fun end() {
        for (n in nodes.values) n.intensity = 0.0
        state = State.Done
    }

    private fun redistributeIntensity(amount: Double, targets: Set<Node>) {
        var energy = 0.0
        for (n in nodes.values) {
            val e = n.intensity * amount
            n.intensity -= e
            energy += e
        }
        energy /= targets.size
        for (n in targets) n.intensity *= 1f + amount * 4
    }

    private fun averageIntensity(targets: Set<Node>) {
        val energy = targets.sumOf { it.intensity } / targets.size
        for (n in targets) n.intensity += energy
    }

    private fun nextNodeToExpand(): Node {
        while (toExpand.isNotEmpty()) {
            val n = toExpand.remove()
            if (n.pos !in nodes) return n
        }
        error("wat")
    }

    private val bgTile = Tile.newBuilder()
        .withBackgroundColor(TileColor.create(0, 0, 0))
        .withCharacter(' ')
        .build()
    private var bgIntensity = mapOf<Position, Double>()
    fun drawTo(screen: Screen) {
        bgIntensity = gauss(1.1, 20) { nodes[it]?.intensity ?: -10.0 }
        screen.fill(bgTile)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val pos = Position.create(x, y)
                screen.draw(tile(pos), pos)
            }
        }
    }

    fun tile(pos: Position): Tile {
        val bgIntensity = maxOf(0.0, (bgIntensity[pos] ?: 0.0) * 0.8 - 0.0)
        val n = nodes[pos]
        val fgIntensity = maxOf(n?.intensity ?: 0.0, bgIntensity)
        val char = n?.char ?: ' '
        return Tile.newBuilder()
            .withBackgroundColor(intensityColor(bgIntensity))
            .withForegroundColor(intensityColor(fgIntensity))
            .withCharacter(char)
            .build()
    }

    private fun intensityColor(intensity: Double): TileColor {
        val rg = 1.03f * (tanh(2f * intensity - 2f) + 1f) / 2f
        val b = 1.03f * (tanh(3f * intensity - 2f) + 1f) / 2f
        return TileColor.create(
            (rg * 255).roundToInt().coerceIn(0..255),
            (rg * 255).roundToInt().coerceIn(0..255),
            (b * 255).roundToInt().coerceIn(0..255),
        )
    }
}

fun random(range: IntRange) = range.random()

fun gauss(sigma: Double, size: Int, init: (Position) -> Double): Map<Position, Double> {
    val kernel = (-size..size).associateWith { (1 / sigma / sqrt(2 * PI)) * exp(-0.5f * (it / sigma) * (it / sigma)) }
    val sum = kernel.values.sum()
    val tmp = buildMap {
        for (x in 0 until width) {
            for (y in 0 until height) {
                val v = kernel.entries.sumOf { (d, k) -> init(Position.create(x + d, y)) * k / sum }
                this[Position.create(x, y)] = v
            }
        }
    }
    return buildMap {
        for (x in 0 until width) {
            for (y in 0 until height) {
                val v = kernel.entries.sumOf { (d, k) -> (tmp[Position.create(x, y + d)] ?: 0.0) * k / sum }
                this[Position.create(x, y)] = v
            }
        }
    }
}
