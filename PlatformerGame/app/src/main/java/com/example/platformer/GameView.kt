package com.example.platformer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private var thread: Thread? = null
    @Volatile private var running = false

    // --- Игровые константы ---
    private val gravity = 1800f          // px/s^2
    private val jumpVelocity = -820f     // px/s
    private val groundSpeedStart = 420f  // px/s, скорость мира
    private var groundSpeed = groundSpeedStart

    // --- Игрок ---
    private var playerX = 0f
    private var playerY = 0f
    private var playerVelY = 0f
    private val playerSize = 90f
    private var onGround = false
    private var groundY = 0f

    // --- Состояние ---
    private var screenW = 0
    private var screenH = 0
    private var score = 0
    private var best = 0
    private var gameOver = false
    private var started = false
    private var elapsedTime = 0f

    // --- Препятствия / платформы ---
    private data class Obstacle(var x: Float, var y: Float, var w: Float, var h: Float, val type: Int)
    private val obstacles = mutableListOf<Obstacle>()
    private var distanceSinceLastObstacle = 0f
    private var nextObstacleGap = 500f

    // --- Paint объекты (переиспользуем, не создаём в draw) ---
    private val bgPaint = Paint().apply { color = Color.parseColor("#87CEEB") }
    private val groundPaint = Paint().apply { color = Color.parseColor("#4CAF50") }
    private val playerPaint = Paint().apply { color = Color.parseColor("#FF5722") }
    private val obstaclePaint = Paint().apply { color = Color.parseColor("#795548") }
    private val platformPaint = Paint().apply { color = Color.parseColor("#9E9E9E") }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 64f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val bigTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 96f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 3f, 3f, Color.BLACK)
    }
    private val hintPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private var lastFrameTime = 0L

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // --- SurfaceHolder.Callback ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        screenW = width
        screenH = height
        resetGame()
        running = true
        thread = Thread(this)
        thread?.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        screenW = w
        screenH = h
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
    }

    fun pause() {
        running = false
        try {
            thread?.join()
        } catch (e: InterruptedException) {
            // игнорируем, поток и так завершится
        }
    }

    fun resume() {
        if (running) return
        running = true
        thread = Thread(this)
        thread?.start()
    }

    // --- Игровой цикл ---
    override fun run() {
        lastFrameTime = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - lastFrameTime) / 1_000_000_000f
            lastFrameTime = now
            dt = min(dt, 0.033f) // защита от рывков при паузах

            if (started && !gameOver) {
                update(dt)
            }

            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    draw(canvas)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }

            // Ограничиваем частоту кадров примерно до 60 FPS
            val frameTimeMs = (System.nanoTime() - now) / 1_000_000
            val targetFrameTimeMs = 16L
            if (frameTimeMs < targetFrameTimeMs) {
                try {
                    Thread.sleep(targetFrameTimeMs - frameTimeMs)
                } catch (e: InterruptedException) {
                    // поток завершается, выходим из цикла на следующей проверке running
                }
            }
        }
    }

    private fun resetGame() {
        groundY = screenH * 0.75f
        playerX = screenW * 0.2f
        playerY = groundY - playerSize
        playerVelY = 0f
        onGround = true
        groundSpeed = groundSpeedStart
        score = 0
        elapsedTime = 0f
        gameOver = false
        obstacles.clear()
        distanceSinceLastObstacle = 0f
        nextObstacleGap = 500f
    }

    private fun update(dt: Float) {
        elapsedTime += dt
        // Постепенно увеличиваем скорость - сложность растёт со временем
        groundSpeed = groundSpeedStart + elapsedTime * 12f

        // Физика игрока
        playerVelY += gravity * dt
        playerY += playerVelY * dt

        if (playerY + playerSize >= groundY) {
            playerY = groundY - playerSize
            playerVelY = 0f
            onGround = true
        } else {
            onGround = false
        }

        // Спавн препятствий
        distanceSinceLastObstacle += groundSpeed * dt
        if (distanceSinceLastObstacle >= nextObstacleGap) {
            spawnObstacle()
            distanceSinceLastObstacle = 0f
            nextObstacleGap = Random.nextInt(380, 620).toFloat()
        }

        // Двигаем препятствия и проверяем столкновения
        val playerRect = RectF(playerX, playerY, playerX + playerSize, playerY + playerSize)
        val iterator = obstacles.iterator()
        while (iterator.hasNext()) {
            val obs = iterator.next()
            obs.x -= groundSpeed * dt
            if (obs.x + obs.w < 0) {
                iterator.remove()
                score += 1
                continue
            }
            val obsRect = RectF(obs.x, obs.y, obs.x + obs.w, obs.y + obs.h)
            if (RectF.intersects(playerRect, obsRect)) {
                gameOver = true
                if (score > best) best = score
            }
        }
    }

    private fun spawnObstacle() {
        // type 0 = препятствие на земле (нужно прыгнуть)
        // type 1 = платформа в воздухе (нужно проскочить под ней или запрыгнуть точно)
        val type = if (Random.nextFloat() < 0.75f) 0 else 1
        if (type == 0) {
            val h = Random.nextInt(70, 140).toFloat()
            obstacles.add(Obstacle(screenW.toFloat(), groundY - h, 60f, h, 0))
        } else {
            val h = 40f
            val gapAboveGround = Random.nextInt(180, 260).toFloat()
            obstacles.add(Obstacle(screenW.toFloat(), groundY - gapAboveGround, 160f, h, 1))
        }
    }

    private fun draw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), bgPaint)
        canvas.drawRect(0f, groundY, screenW.toFloat(), screenH.toFloat(), groundPaint)

        for (obs in obstacles) {
            val p = if (obs.type == 0) obstaclePaint else platformPaint
            canvas.drawRect(obs.x, obs.y, obs.x + obs.w, obs.y + obs.h, p)
        }

        canvas.drawRect(playerX, playerY, playerX + playerSize, playerY + playerSize, playerPaint)

        canvas.drawText("Счёт: $score", 40f, 90f, textPaint)
        canvas.drawText("Рекорд: $best", 40f, 160f, textPaint)

        if (!started) {
            canvas.drawText("PLATFORM JUMP", screenW / 2f, screenH / 2f - 40f, bigTextPaint)
            canvas.drawText("Тапни, чтобы начать", screenW / 2f, screenH / 2f + 40f, hintPaint)
        } else if (gameOver) {
            canvas.drawText("ИГРА ОКОНЧЕНА", screenW / 2f, screenH / 2f - 40f, bigTextPaint)
            canvas.drawText("Счёт: $score  •  Тапни для рестарта", screenW / 2f, screenH / 2f + 40f, hintPaint)
        }
    }

    // --- Управление тапом ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (!started) {
                started = true
            } else if (gameOver) {
                resetGame()
                started = true
            } else if (onGround) {
                playerVelY = jumpVelocity
                onGround = false
            }
        }
        return true
    }
}
