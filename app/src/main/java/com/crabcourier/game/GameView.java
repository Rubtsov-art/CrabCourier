package com.crabcourier.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

public class GameView extends View {
    private static final long LEVEL_MS = 45_000L;
    private static final int START_LIVES = 5;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Random rng = new Random();
    private final ArrayList<Neighbor> neighbors = new ArrayList<>();
    private final ArrayList<EnemyBurger> enemyBurgers = new ArrayList<>();
    private final ArrayList<Splat> splats = new ArrayList<>();
    private final ArrayList<Bird> birds = new ArrayList<>();
    private final AudioEngine audio = new AudioEngine();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;

    private enum Screen { MENU, PLAYING, GAME_OVER }
    private enum ShotType { NEIGHBOR, ZEBRA, BONUS }
    private enum BonusType { SLOW, LIFE, FAST, DOUBLE }

    private Screen screen = Screen.MENU;
    private int score;
    private int bestScore;
    private int lives = START_LIVES;
    private int level = 1;
    private int floorNumber = 1;
    private int servedThisFloor;
    private long levelRemaining = LEVEL_MS;
    private long lastFrame;
    private long spawnCooldown;
    private long bonusCooldown;
    private long slowUntil;
    private long fastUntil;
    private long doubleUntil;
    private long messageUntil;
    private String message = "";

    private float scrollProgress;
    private boolean scrolling;
    private long transitionMs;
    private String transitionText = "";

    private BurgerShot shot;
    private Bonus bonus;
    private Zebra zebra;
    private int zebraLevel;
    private long zebraSpawnAt;
    private int zebraAttempts;
    private boolean zebraHit;
    private boolean castleMode;
    private int returnLevelAfterCastle;

    private float planeX = -1000f;
    private float planeY;
    private boolean planeGag;
    private long planeGagUntil;

    private float w, h;
    private final RectF startButton = new RectF();

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(5f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        prefs = context.getSharedPreferences("crab_courier", Context.MODE_PRIVATE);
        bestScore = prefs.getInt("best_score", 0);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        for (int i = 0; i < 4; i++) birds.add(new Bird(rng.nextFloat(), rng.nextFloat()));
        audio.setMusicPlaying(true);
        audio.setMusicMode(0);
    }

    public void onHostPause() { audio.setMusicPlaying(false); }
    public void onHostResume() { audio.setMusicPlaying(true); }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        audio.shutdown();
    }

    @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        w = width;
        h = height;
        float bw = w * 0.56f;
        float bh = Math.max(120f, h * 0.075f);
        startButton.set((w - bw) / 2f, h * 0.69f, (w + bw) / 2f, h * 0.69f + bh);
        planeY = h * 0.11f;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        long now = SystemClock.uptimeMillis();
        if (lastFrame == 0) lastFrame = now;
        long dt = Math.min(34L, Math.max(0L, now - lastFrame));
        lastFrame = now;

        updateDecorations(dt, now);
        if (screen == Screen.PLAYING) updateGame(dt, now);

        drawBackdrop(c, now);
        drawWorld(c, now);
        drawDecorations(c, now);

        if (screen == Screen.MENU) drawMenu(c);
        else {
            drawCrab(c, w * 0.5f, h * 0.885f, now);
            drawShot(c, now);
            drawEnemyBurgers(c, dt);
            drawSplats(c, dt);
            drawHud(c, now);
            if (screen == Screen.GAME_OVER) drawGameOver(c);
            else drawTransition(c);
        }
        postInvalidateOnAnimation();
    }

    private void startGame() {
        score = 0;
        lives = START_LIVES;
        level = 1;
        floorNumber = 1;
        servedThisFloor = 0;
        levelRemaining = LEVEL_MS;
        spawnCooldown = 450;
        bonusCooldown = 6500 + rng.nextInt(3500);
        slowUntil = fastUntil = doubleUntil = 0;
        neighbors.clear(); enemyBurgers.clear(); splats.clear();
        shot = null; bonus = null; zebra = null;
        castleMode = false; zebraHit = false; zebraAttempts = 0;
        zebraLevel = 3 + rng.nextInt(3);
        zebraSpawnAt = 8_000 + rng.nextInt(8_000);
        transitionMs = 1200;
        transitionText = "УРОВЕНЬ 1";
        scrolling = false;
        scrollProgress = 0;
        screen = Screen.PLAYING;
        audio.setMusicMode(0);
        audio.playLevelUp();
    }

    private void gameOver() {
        screen = Screen.GAME_OVER;
        neighbors.clear();
        shot = null;
        if (score > bestScore) {
            bestScore = score;
            prefs.edit().putInt("best_score", bestScore).apply();
        }
        audio.playMiss();
        audio.setMusicMode(0);
    }

    private void updateGame(long dt, long now) {
        if (transitionMs > 0) {
            transitionMs -= dt;
            return;
        }
        if (scrolling) {
            scrollProgress += dt / 720f;
            if (scrollProgress >= 1f) {
                scrollProgress = 0f;
                scrolling = false;
                floorNumber++;
                servedThisFloor = 0;
                spawnCooldown = 180;
            }
            return;
        }

        // Gameplay clocks stop during a delivery animation so the player is never punished for waiting.
        if (shot != null) {
            updateShot(dt, now);
            return;
        }

        levelRemaining -= dt;
        if (levelRemaining <= 0) {
            finishLevel();
            return;
        }

        float timeScale = now < slowUntil ? 0.55f : 1f;
        updateNeighbors((long) (dt * timeScale), now);
        updateZebra(dt, now);
        updateBonus(dt, now);

        if (screen != Screen.PLAYING || transitionMs > 0) return;

        if (servedThisFloor >= floorGoal() && neighbors.isEmpty()) {
            scrolling = true;
            scrollProgress = 0;
            transitionText = "ВЫШЕ!";
            audio.playLevelUp();
            return;
        }

        if (servedThisFloor < floorGoal()) {
            spawnCooldown -= dt;
            if (spawnCooldown <= 0 && neighbors.size() < maxNeighbors()) {
                spawnNeighbor(now);
                long base = Math.max(430, 1050 - level * 55L);
                spawnCooldown = base + rng.nextInt(500);
            }
        }
    }

    private void finishLevel() {
        neighbors.clear();
        bonus = null;
        zebra = null;
        shot = null;
        servedThisFloor = 0;
        scrolling = false;
        scrollProgress = 0;
        levelRemaining = LEVEL_MS;
        if (castleMode) {
            castleMode = false;
            level = returnLevelAfterCastle;
            transitionText = "ОБРАТНО В ГОРОД • УРОВЕНЬ " + level;
        } else {
            level++;
            transitionText = "УРОВЕНЬ " + level;
        }
        transitionMs = 1450;
        spawnCooldown = 400;
        bonusCooldown = 4500 + rng.nextInt(4000);
        audio.setMusicMode(castleMode ? 3 : (level - 1) % 3);
        audio.playLevelUp();
    }

    private int maxNeighbors() {
        int effective = castleMode ? Math.max(4, level) : level;
        return Math.min(6, 2 + (effective - 1) / 2);
    }

    private int floorGoal() { return 4 + Math.min(3, Math.max(0, level - 1) / 3); }

    private long neighborWaitMs() {
        // Starts forgiving, then settles near 2.4 s while simultaneous targets carry most of the difficulty.
        long value = 4200L - (level - 1) * 180L;
        if (castleMode) value += 220;
        return Math.max(2400L, value);
    }

    private void spawnNeighbor(long now) {
        boolean[] used = new boolean[12];
        for (Neighbor n : neighbors) used[n.slot] = true;
        ArrayList<Integer> free = new ArrayList<>();
        for (int i = 0; i < 12; i++) if (!used[i]) free.add(i);
        if (free.isEmpty()) return;
        int slot = free.get(rng.nextInt(free.size()));
        long wait = neighborWaitMs() + rng.nextInt(550) - 250;
        neighbors.add(new Neighbor(slot, Math.max(1900, wait), rng.nextInt(6), now));
    }

    private void updateNeighbors(long dt, long now) {
        Iterator<Neighbor> it = neighbors.iterator();
        while (it.hasNext()) {
            Neighbor n = it.next();
            n.remaining -= dt;
            if (!n.warned && n.remaining < 1000) {
                n.warned = true;
                n.nextRant = now;
            }
            if (n.warned && now >= n.nextRant && n.remaining > 0) {
                audio.playAngry();
                n.nextRant = now + 520 + rng.nextInt(220);
            }
            if (n.remaining <= 0) {
                RectF r = windowRect(n.slot);
                enemyBurgers.add(new EnemyBurger(r.centerX(), r.centerY(), now));
                it.remove();
                lives--;
                audio.playMiss();
                vibrate(70);
                if (lives <= 0) {
                    gameOver();
                    return;
                }
            }
        }
    }

    private void updateZebra(long dt, long now) {
        if (castleMode || zebraHit || level < 3 || level > 5 || level != zebraLevel) return;
        long elapsed = LEVEL_MS - levelRemaining;
        if (zebra == null && zebraAttempts < 2 && elapsed >= zebraSpawnAt) {
            float x = w * (0.18f + rng.nextFloat() * 0.64f);
            float y = h * (0.26f + rng.nextFloat() * 0.37f);
            zebra = new Zebra(x, y, 5200);
            zebraAttempts++;
        }
        if (zebra != null) {
            zebra.remaining -= dt;
            zebra.phase += dt * 0.008f;
            if (zebra.remaining <= 0) {
                zebra = null;
                zebraSpawnAt = elapsed + 7000 + rng.nextInt(4000);
            }
        }
    }

    private void updateBonus(long dt, long now) {
        if (bonus != null) {
            bonus.remaining -= dt;
            bonus.phase += dt * 0.006f;
            if (bonus.remaining <= 0) bonus = null;
            return;
        }
        bonusCooldown -= dt;
        if (bonusCooldown <= 0) {
            BonusType type = BonusType.values()[rng.nextInt(BonusType.values().length)];
            bonus = new Bonus(w * (0.16f + rng.nextFloat() * 0.68f), h * (0.25f + rng.nextFloat() * 0.42f), type);
            bonusCooldown = 9500 + rng.nextInt(6000);
        }
    }

    private void launchShot(float tx, float ty, ShotType type, Object target, long now) {
        if (shot != null) return;
        float sx = w * 0.5f;
        float sy = h * 0.84f;
        long duration = now < fastUntil ? 220 : 390;
        shot = new BurgerShot(sx, sy, tx, ty, type, target, now, duration);
        audio.playThrow();
        vibrate(18);
    }

    private void updateShot(long dt, long now) {
        if (shot == null) return;
        float t = Math.min(1f, (now - shot.started) / (float) shot.duration);
        shot.progress = t;
        if (t < 1f) return;

        if (shot.type == ShotType.NEIGHBOR && shot.target instanceof Neighbor) {
            Neighbor n = (Neighbor) shot.target;
            if (neighbors.remove(n)) {
                int base = 100;
                int quick = (int) (60f * Math.max(0f, n.remaining / (float) n.total));
                int gained = base + quick;
                if (now < doubleUntil) gained *= 2;
                score += gained;
                servedThisFloor++;
                audio.playServe();
                message = "+" + gained;
                messageUntil = now + 620;
            }
        } else if (shot.type == ShotType.ZEBRA && zebra != null) {
            score += 500;
            zebraHit = true;
            zebra = null;
            activateCastle();
        } else if (shot.type == ShotType.BONUS && bonus != null) {
            applyBonus(bonus.type, now);
            bonus = null;
        }
        shot = null;
    }

    private void activateCastle() {
        castleMode = true;
        returnLevelAfterCastle = level + 1;
        levelRemaining = LEVEL_MS;
        neighbors.clear();
        servedThisFloor = 0;
        scrolling = false;
        transitionMs = 1650;
        transitionText = "СЕКРЕТНЫЙ ЗАМОК!";
        audio.setMusicMode(3);
        audio.playSecret();
        message = "СЕКРЕТ +500";
        messageUntil = SystemClock.uptimeMillis() + 1500;
    }

    private void applyBonus(BonusType type, long now) {
        switch (type) {
            case SLOW:
                slowUntil = now + 8000;
                message = "ВРЕМЯ ЗАМЕДЛЕНО!";
                break;
            case LIFE:
                lives = Math.min(START_LIVES, lives + 1);
                message = "+1 ЖИЗНЬ";
                break;
            case FAST:
                fastUntil = now + 8000;
                message = "ТУРБО-БРОСОК!";
                break;
            case DOUBLE:
                doubleUntil = now + 8000;
                message = "ОЧКИ ×2!";
                break;
        }
        messageUntil = now + 1400;
        score += 50;
        audio.playBonus();
        vibrate(35);
    }

    private void updateDecorations(long dt, long now) {
        if (w <= 0 || h <= 0) return;
        for (Bird b : birds) {
            b.x += dt * b.speed / Math.max(1f, w);
            b.flap += dt * 0.018f;
            if (b.x > 1.15f) {
                b.x = -0.12f;
                b.y = 0.12f + rng.nextFloat() * 0.22f;
                b.speed = 0.025f + rng.nextFloat() * 0.025f;
            }
        }
        planeX += dt * w * 0.000045f;
        if (planeX > w + 850) {
            planeX = -1200;
            planeY = h * (0.08f + rng.nextFloat() * 0.07f);
            planeGag = false;
        }
        if (planeGag && now > planeGagUntil) planeGag = false;
    }

    private RectF windowRect(int slot) {
        int col = slot % 3;
        int row = slot / 3;
        float buildingL = w * 0.055f;
        float buildingR = w * 0.945f;
        float top = h * 0.18f;
        float bottom = h * 0.745f;
        float bw = buildingR - buildingL;
        float rowH = (bottom - top) / 4f;
        float winW = bw * 0.235f;
        float winH = rowH * 0.67f;
        float gap = (bw - 3f * winW) / 4f;
        float x = buildingL + gap + col * (winW + gap);
        float scroll = scrolling ? rowH * ease(scrollProgress) : 0f;
        float y = top + row * rowH + rowH * 0.15f + scroll;
        return new RectF(x, y, x + winW, y + winH);
    }

    private void drawBackdrop(Canvas c, long now) {
        LinearGradient sky = new LinearGradient(0, 0, 0, h,
                castleMode ? Color.rgb(54, 38, 102) : Color.rgb(86, 198, 244),
                castleMode ? Color.rgb(27, 21, 63) : Color.rgb(225, 247, 255),
                Shader.TileMode.CLAMP);
        p.setShader(sky);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        if (!castleMode) {
            p.setColor(Color.argb(225, 255, 255, 255));
            drawCloud(c, w * .18f, h * .10f, w * .12f);
            drawCloud(c, w * .78f, h * .15f, w * .10f);
        } else {
            p.setColor(Color.rgb(247, 226, 159));
            c.drawCircle(w * .82f, h * .09f, w * .075f, p);
            p.setColor(Color.argb(90, 255, 255, 255));
            for (int i = 0; i < 28; i++) {
                float sx = ((i * 137) % 1000) / 1000f * w;
                float sy = ((i * 223) % 600) / 1000f * h;
                float r = 2f + (i % 3);
                c.drawCircle(sx, sy, r, p);
            }
        }
    }

    private void drawWorld(Canvas c, long now) {
        float buildingL = w * 0.045f;
        float buildingR = w * 0.955f;
        float top = h * 0.145f;
        float bottom = h * 0.81f;
        RectF facade = new RectF(buildingL, top, buildingR, bottom);

        if (!castleMode) {
            p.setColor(Color.rgb(255, 222, 159));
            c.drawRoundRect(facade, w * .025f, w * .025f, p);
            p.setColor(Color.rgb(241, 149, 105));
            c.drawRect(buildingL, top, buildingL + w * .035f, bottom, p);
            c.drawRect(buildingR - w * .035f, top, buildingR, bottom, p);
            p.setColor(Color.argb(75, 152, 81, 59));
            float rowH = (h * 0.745f - h * 0.18f) / 4f;
            float scroll = scrolling ? rowH * ease(scrollProgress) : 0f;
            for (int r = -1; r <= 4; r++) {
                float y = h * 0.18f + r * rowH + scroll;
                c.drawRect(buildingL, y - 5, buildingR, y + 4, p);
            }
        } else {
            LinearGradient stone = new LinearGradient(buildingL, top, buildingR, bottom,
                    Color.rgb(105, 92, 139), Color.rgb(61, 52, 94), Shader.TileMode.CLAMP);
            p.setShader(stone);
            c.drawRoundRect(facade, w * .02f, w * .02f, p);
            p.setShader(null);
            p.setColor(Color.argb(85, 31, 24, 50));
            for (int y = (int) top; y < bottom; y += Math.max(25, (int)(h * .028f))) {
                float off = ((y / 40) % 2) * w * .06f;
                for (float x = buildingL - off; x < buildingR; x += w * .18f) {
                    c.drawRect(x, y, x + w * .16f, y + 4, p);
                }
            }
        }

        for (int slot = 0; slot < 12; slot++) drawWindow(c, slot, now);
        if (scrolling) for (int col = 0; col < 3; col++) drawExtraTopWindow(c, col);
        drawFloorCounter(c);
        drawNeighbors(c, now);
        if (zebra != null) drawZebra(c, zebra, now);
        if (bonus != null) drawBonus(c, bonus, now);
    }

    private void drawWindow(Canvas c, int slot, long now) {
        RectF r = windowRect(slot);
        float rim = w * .012f;
        p.setColor(castleMode ? Color.rgb(48, 39, 73) : Color.rgb(166, 83, 64));
        c.drawRoundRect(new RectF(r.left-rim, r.top-rim, r.right+rim, r.bottom+rim), rim, rim, p);
        LinearGradient glass = new LinearGradient(r.left, r.top, r.right, r.bottom,
                castleMode ? Color.rgb(96, 198, 221) : Color.rgb(89, 176, 217),
                castleMode ? Color.rgb(31, 87, 132) : Color.rgb(39, 103, 153), Shader.TileMode.CLAMP);
        p.setShader(glass);
        c.drawRoundRect(r, rim * .55f, rim * .55f, p);
        p.setShader(null);
        p.setColor(Color.argb(115, 255, 255, 255));
        c.drawRect(r.left + r.width() * .13f, r.top + r.height() * .12f, r.left + r.width() * .18f, r.bottom - r.height() * .14f, p);
        p.setColor(Color.rgb(238, 234, 218));
        c.drawRect(r.centerX() - 3, r.top, r.centerX() + 3, r.bottom, p);
        c.drawRect(r.left, r.centerY() - 3, r.right, r.centerY() + 3, p);
        p.setColor(castleMode ? Color.rgb(61, 44, 79) : Color.rgb(226, 111, 75));
        c.drawRect(r.left - rim, r.bottom + rim * .3f, r.right + rim, r.bottom + rim * 1.35f, p);
    }

    private void drawExtraTopWindow(Canvas c, int col) {
        float buildingL = w * 0.055f, buildingR = w * 0.945f;
        float top = h * 0.18f, bottom = h * 0.745f;
        float bw = buildingR - buildingL;
        float rowH = (bottom - top) / 4f;
        float winW = bw * 0.235f, winH = rowH * 0.67f;
        float gap = (bw - 3f * winW) / 4f;
        float x = buildingL + gap + col * (winW + gap);
        float y = top - rowH + rowH * .15f + rowH * ease(scrollProgress);
        RectF r = new RectF(x, y, x + winW, y + winH);
        float rim = w * .012f;
        p.setColor(castleMode ? Color.rgb(48,39,73) : Color.rgb(166,83,64));
        c.drawRoundRect(new RectF(r.left-rim,r.top-rim,r.right+rim,r.bottom+rim),rim,rim,p);
        p.setColor(castleMode ? Color.rgb(61,135,165) : Color.rgb(63,142,186));
        c.drawRoundRect(r,rim*.5f,rim*.5f,p);
    }

    private void drawNeighbors(Canvas c, long now) {
        for (Neighbor n : neighbors) {
            RectF r = windowRect(n.slot);
            float bob = (float)Math.sin((now - n.born) * .006) * r.height() * .018f;
            drawPerson(c, r.centerX(), r.bottom - r.height() * .16f + bob, r.width() * .78f, n.kind, n.warned);

            float frac = Math.max(0f, n.remaining / (float)n.total);
            RectF meter = new RectF(r.left + r.width()*.1f, r.top + r.height()*.06f,
                    r.right - r.width()*.1f, r.top + r.height()*.105f);
            p.setColor(Color.argb(120, 20, 25, 35)); c.drawRoundRect(meter, 10,10,p);
            p.setColor(frac > .45f ? Color.rgb(108,225,111) : (frac > .22f ? Color.rgb(255,194,65) : Color.rgb(244,76,76)));
            c.drawRoundRect(new RectF(meter.left, meter.top, meter.left + meter.width()*frac, meter.bottom),10,10,p);

            if (n.warned) drawAngryBubble(c, r.centerX(), r.top - h*.012f, now);
        }
    }

    private void drawPerson(Canvas c, float cx, float baseY, float size, int kind, boolean angry) {
        int[] shirts = {0xff4f79d9,0xff34a071,0xffcf5f86,0xffe68b3c,0xff755fc8,0xff2f9aa8};
        int[] hairs = {0xff4a2d22,0xff2c2522,0xffb36b2c,0xff1f1d25,0xff6b3f2a,0xffd8b04a};
        int[] skins = {0xffffc89d,0xfff2b182,0xffd99163,0xffa86645,0xffffd3af,0xffc77f59};
        float headR = size * .18f;
        float headY = baseY - size * .47f;
        p.setColor(shirts[kind % shirts.length]);
        c.drawRoundRect(new RectF(cx-size*.28f, baseY-size*.34f, cx+size*.28f, baseY+size*.12f), size*.1f,size*.1f,p);
        p.setColor(skins[kind % skins.length]);
        c.drawCircle(cx, headY, headR, p);
        p.setColor(hairs[kind % hairs.length]);
        RectF hair = new RectF(cx-headR*1.02f, headY-headR*1.05f, cx+headR*1.02f, headY-headR*.15f);
        c.drawArc(hair, 180, 180, true, p);
        p.setColor(skins[kind % skins.length]);
        c.drawCircle(cx-size*.24f, baseY-size*.08f, size*.065f,p);
        c.drawCircle(cx+size*.24f, baseY-size*.08f, size*.065f,p);
        p.setColor(Color.WHITE); c.drawCircle(cx-headR*.38f, headY-headR*.1f, headR*.24f,p); c.drawCircle(cx+headR*.38f, headY-headR*.1f, headR*.24f,p);
        p.setColor(Color.rgb(35,31,35)); c.drawCircle(cx-headR*.34f,headY-headR*.08f,headR*.10f,p); c.drawCircle(cx+headR*.34f,headY-headR*.08f,headR*.10f,p);
        stroke.setStrokeWidth(Math.max(3f,size*.025f)); stroke.setColor(Color.rgb(55,35,30));
        if (angry) {
            c.drawLine(cx-headR*.62f,headY-headR*.5f,cx-headR*.15f,headY-headR*.34f,stroke);
            c.drawLine(cx+headR*.15f,headY-headR*.34f,cx+headR*.62f,headY-headR*.5f,stroke);
            c.drawArc(new RectF(cx-headR*.45f,headY+headR*.25f,cx+headR*.45f,headY+headR*.85f),200,140,false,stroke);
        } else {
            c.drawLine(cx-headR*.62f,headY-headR*.42f,cx-headR*.15f,headY-headR*.48f,stroke);
            c.drawLine(cx+headR*.15f,headY-headR*.48f,cx+headR*.62f,headY-headR*.42f,stroke);
            c.drawArc(new RectF(cx-headR*.4f,headY+headR*.12f,cx+headR*.4f,headY+headR*.62f),15,150,false,stroke);
        }
    }

    private void drawAngryBubble(Canvas c, float cx, float cy, long now) {
        float bw = w * .11f, bh = h * .055f;
        RectF b = new RectF(cx-bw*.5f,cy-bh,cx+bw*.5f,cy);
        p.setColor(Color.WHITE); c.drawRoundRect(b,bh*.35f,bh*.35f,p);
        Path tail = new Path(); tail.moveTo(cx-bw*.14f,cy);tail.lineTo(cx,cy+bh*.25f);tail.lineTo(cx+bw*.08f,cy);tail.close();c.drawPath(tail,p);
        p.setColor(Color.rgb(255,190,35));
        Path bolt = new Path(); bolt.moveTo(cx-bw*.15f,cy-bh*.78f);bolt.lineTo(cx+bw*.02f,cy-bh*.78f);bolt.lineTo(cx-bw*.05f,cy-bh*.48f);bolt.lineTo(cx+bw*.16f,cy-bh*.48f);bolt.lineTo(cx-bw*.08f,cy-bh*.12f);bolt.lineTo(cx,cy-bh*.39f);bolt.lineTo(cx-bw*.16f,cy-bh*.39f);bolt.close(); c.drawPath(bolt,p);
        p.setColor(Color.rgb(68,55,70));p.setTextSize(Math.max(18f,w*.03f));p.setTextAlign(Paint.Align.CENTER);c.drawText("!?#",cx+bw*.23f,cy-bh*.33f,p);p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawCrab(Canvas c, float cx, float cy, long now) {
        float s = w * .24f;
        float bounce = screen == Screen.PLAYING ? (float)Math.sin(now*.007)*h*.0025f : (float)Math.sin(now*.003)*h*.004f;
        cy += bounce;
        p.setColor(Color.argb(45,0,0,0));c.drawOval(new RectF(cx-s*.43f,cy+s*.36f,cx+s*.43f,cy+s*.50f),p);
        p.setColor(Color.rgb(255,196,45));c.drawRoundRect(new RectF(cx+s*.24f,cy-s*.38f,cx+s*.57f,cy+s*.18f),s*.08f,s*.08f,p);
        p.setColor(Color.rgb(211,141,25));c.drawRect(cx+s*.30f,cy-s*.20f,cx+s*.50f,cy-s*.15f,p);
        stroke.setStrokeWidth(s*.065f);stroke.setColor(Color.rgb(203,63,52));
        for(int i=-1;i<=1;i+=2){c.drawLine(cx+i*s*.18f,cy+s*.23f,cx+i*s*.34f,cy+s*.40f,stroke);c.drawLine(cx+i*s*.04f,cy+s*.26f,cx+i*s*.13f,cy+s*.46f,stroke);}
        p.setColor(Color.rgb(52,91,153));c.drawRoundRect(new RectF(cx-s*.32f,cy+s*.06f,cx+s*.32f,cy+s*.34f),s*.08f,s*.08f,p);
        p.setColor(Color.rgb(40,72,126));c.drawRect(cx-s*.02f,cy+s*.13f,cx+s*.02f,cy+s*.34f,p);
        p.setColor(Color.rgb(250,246,225));c.drawRect(cx-s*.28f,cy+s*.25f,cx-s*.06f,cy+s*.29f,p);c.drawRect(cx+s*.06f,cy+s*.25f,cx+s*.28f,cy+s*.29f,p);
        LinearGradient body = new LinearGradient(cx-s*.36f,cy-s*.32f,cx+s*.36f,cy+s*.25f,Color.rgb(249,104,73),Color.rgb(202,55,48),Shader.TileMode.CLAMP);
        p.setShader(body);c.drawOval(new RectF(cx-s*.38f,cy-s*.32f,cx+s*.38f,cy+s*.24f),p);p.setShader(null);
        p.setColor(Color.rgb(232,76,60));c.drawCircle(cx-s*.48f,cy-s*.08f,s*.15f,p);c.drawCircle(cx+s*.48f,cy-s*.08f,s*.15f,p);
        p.setColor(Color.rgb(255,151,105));c.drawArc(new RectF(cx-s*.60f,cy-s*.21f,cx-s*.36f,cy+s*.03f),210,135,true,p);c.drawArc(new RectF(cx+s*.36f,cy-s*.21f,cx+s*.60f,cy+s*.03f),-165,135,true,p);
        p.setColor(Color.rgb(229,71,58));c.drawCircle(cx-s*.18f,cy-s*.37f,s*.10f,p);c.drawCircle(cx+s*.18f,cy-s*.37f,s*.10f,p);
        p.setColor(Color.WHITE);c.drawCircle(cx-s*.18f,cy-s*.39f,s*.072f,p);c.drawCircle(cx+s*.18f,cy-s*.39f,s*.072f,p);
        p.setColor(Color.rgb(32,33,39));c.drawCircle(cx-s*.16f,cy-s*.39f,s*.028f,p);c.drawCircle(cx+s*.16f,cy-s*.39f,s*.028f,p);
        stroke.setColor(Color.rgb(72,35,30));stroke.setStrokeWidth(s*.035f);c.drawLine(cx-s*.28f,cy-s*.51f,cx-s*.10f,cy-s*.46f,stroke);c.drawLine(cx+s*.10f,cy-s*.46f,cx+s*.28f,cy-s*.51f,stroke);
        stroke.setStrokeWidth(s*.024f);c.drawArc(new RectF(cx-s*.16f,cy-s*.19f,cx+s*.16f,cy+s*.03f),10,160,false,stroke);
        stroke.setColor(Color.rgb(255,210,68));stroke.setStrokeWidth(s*.035f);c.drawArc(new RectF(cx+s*.03f,cy-s*.28f,cx+s*.41f,cy+s*.18f),240,115,false,stroke);
    }

    private void drawShot(Canvas c, long now) {
        if (shot == null) return;
        float t = shot.progress;
        float arc = (float)Math.sin(Math.PI*t) * h*.055f;
        float x = lerp(shot.sx,shot.tx,t);
        float y = lerp(shot.sy,shot.ty,t)-arc;
        drawBurger(c,x,y,w*.075f,1f+t*1.6f);
    }

    private void drawBurger(Canvas c, float cx, float cy, float size, float rot) {
        c.save(); c.rotate(rot*18f,cx,cy);
        float hw=size*.5f, hh=size*.34f;
        p.setColor(Color.rgb(217,104,52));c.drawOval(new RectF(cx-hw,cy-hh*.88f,cx+hw,cy+hh*.02f),p);
        p.setColor(Color.rgb(255,197,95));c.drawOval(new RectF(cx-hw,cy-hh*1.22f,cx+hw,cy-hh*.35f),p);
        p.setColor(Color.rgb(87,173,75));Path lettuce=new Path();lettuce.moveTo(cx-hw,cy-hh*.28f);for(int i=0;i<=8;i++){float x=cx-hw+i*(size/8f);float yy=cy-hh*.25f+(i%2==0?-hh*.16f:hh*.06f);lettuce.lineTo(x,yy);}lettuce.lineTo(cx+hw,cy);lettuce.lineTo(cx-hw,cy);lettuce.close();c.drawPath(lettuce,p);
        p.setColor(Color.rgb(105,55,41));c.drawRoundRect(new RectF(cx-hw*.92f,cy-hh*.03f,cx+hw*.92f,cy+hh*.32f),hh*.16f,hh*.16f,p);
        p.setColor(Color.rgb(255,205,64));Path cheese=new Path();cheese.moveTo(cx-hw*.92f,cy+hh*.22f);cheese.lineTo(cx+hw*.92f,cy+hh*.22f);cheese.lineTo(cx+hw*.65f,cy+hh*.50f);cheese.lineTo(cx,cy+hh*.35f);cheese.lineTo(cx-hw*.72f,cy+hh*.51f);cheese.close();c.drawPath(cheese,p);
        p.setColor(Color.rgb(245,166,70));c.drawOval(new RectF(cx-hw*.96f,cy+hh*.30f,cx+hw*.96f,cy+hh*.82f),p);
        p.setColor(Color.rgb(255,235,160));for(int i=-2;i<=2;i++)c.drawOval(new RectF(cx+i*size*.12f-3,cy-hh*.85f+(i%2)*5,cx+i*size*.12f+3,cy-hh*.85f+8+(i%2)*5),p);
        c.restore();
    }

    private void drawEnemyBurgers(Canvas c, long dt) {
        Iterator<EnemyBurger> it = enemyBurgers.iterator();
        long now=SystemClock.uptimeMillis();
        while(it.hasNext()){
            EnemyBurger b=it.next();float t=Math.min(1f,(now-b.started)/330f);float x=lerp(b.sx,b.tx,t),y=lerp(b.sy,b.ty,t)-(float)Math.sin(Math.PI*t)*h*.035f;drawBurger(c,x,y,w*.085f,t*2f);
            if(t>=1f){splats.add(new Splat(b.tx,b.ty));it.remove();}
        }
    }

    private void drawSplats(Canvas c, long dt) {
        Iterator<Splat> it=splats.iterator();
        while(it.hasNext()){
            Splat s=it.next();s.age+=dt;float a=Math.max(0f,1f-s.age/4200f);p.setColor(Color.argb((int)(190*a),235,164,58));c.drawCircle(s.x,s.y,w*.055f,p);c.drawCircle(s.x-w*.045f,s.y+h*.012f,w*.022f,p);c.drawCircle(s.x+w*.05f,s.y-h*.014f,w*.018f,p);p.setColor(Color.argb((int)(180*a),85,165,66));c.drawRect(s.x-w*.05f,s.y-5,s.x+w*.05f,s.y+7,p);if(s.age>4200)it.remove();
        }
    }

    private void drawBonus(Canvas c, Bonus b, long now) {
        float pulse=1f+(float)Math.sin(b.phase)*.09f;float r=w*.055f*pulse;
        p.setColor(Color.argb(65,255,245,105));c.drawCircle(b.x,b.y,r*1.55f,p);p.setColor(Color.rgb(255,240,105));c.drawCircle(b.x,b.y,r,p);
        p.setColor(Color.rgb(70,58,80));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(r*.92f);
        String s=b.type==BonusType.SLOW?"⏱":b.type==BonusType.LIFE?"♥":b.type==BonusType.FAST?"⚡":"×2";
        c.drawText(s,b.x,b.y+r*.32f,p);p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawZebra(Canvas c, Zebra z, long now) {
        float bounce=(float)Math.sin(z.phase)*h*.008f;float cx=z.x,cy=z.y+bounce,s=w*.15f;
        p.setColor(Color.argb(55,255,255,255));c.drawCircle(cx,cy,s*.58f,p);
        p.setColor(Color.WHITE);c.drawOval(new RectF(cx-s*.34f,cy-s*.28f,cx+s*.34f,cy+s*.25f),p);
        p.setColor(Color.rgb(30,31,36));for(int i=-2;i<=2;i++){float x=cx+i*s*.12f;c.drawRect(x-s*.035f,cy-s*.26f,x+s*.02f,cy+s*.22f,p);}p.setColor(Color.WHITE);c.drawOval(new RectF(cx-s*.22f,cy-s*.44f,cx+s*.22f,cy-s*.05f),p);p.setColor(Color.rgb(25,27,31));c.drawRect(cx-s*.17f,cy-s*.42f,cx-s*.10f,cy-s*.08f,p);c.drawRect(cx+s*.02f,cy-s*.42f,cx+s*.09f,cy-s*.08f,p);
        p.setColor(Color.rgb(32,38,47));c.drawRoundRect(new RectF(cx-s*.16f,cy-s*.34f,cx-s*.02f,cy-s*.24f),8,8,p);c.drawRoundRect(new RectF(cx+s*.02f,cy-s*.34f,cx+s*.16f,cy-s*.24f),8,8,p);c.drawRect(cx-s*.02f,cy-s*.31f,cx+s*.02f,cy-s*.29f,p);
        p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*.032f);c.drawText("?",cx,cy+s*.51f,p);p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawDecorations(Canvas c, long now) {
        for(Bird b:birds){float bx=b.x*w,by=b.y*h;stroke.setColor(castleMode?0xffdcd2ff:0xffffffff);stroke.setStrokeWidth(Math.max(3f,w*.006f));float flap=(float)Math.sin(b.flap)*h*.006f;c.drawArc(new RectF(bx-w*.025f,by-flap,bx,by+h*.018f-flap),205,130,false,stroke);c.drawArc(new RectF(bx,by+h*.018f+flap,bx+w*.025f,by+flap),205,130,false,stroke);}
        p.setColor(Color.rgb(236,241,247));RectF plane=new RectF(planeX,planeY,planeX+w*.24f,planeY+h*.035f);c.drawRoundRect(plane,h*.015f,h*.015f,p);p.setColor(Color.rgb(226,74,65));c.drawRect(planeX+w*.075f,planeY-h*.018f,planeX+w*.13f,planeY+h*.055f,p);p.setColor(Color.rgb(54,99,153));c.drawCircle(planeX+w*.20f,planeY+h*.018f,h*.012f,p);
        if(planeGag){p.setColor(Color.rgb(255,242,172));RectF banner=new RectF(planeX-w*.29f,planeY+h*.006f,planeX-w*.01f,planeY+h*.052f);c.drawRoundRect(banner,12,12,p);p.setColor(Color.rgb(68,50,40));p.setTextSize(w*.026f);p.setTextAlign(Paint.Align.CENTER);c.drawText("КРАБ №1!",banner.centerX(),banner.centerY()+w*.009f,p);p.setTextAlign(Paint.Align.LEFT);}
    }

    private void drawHud(Canvas c, long now) {
        RectF hud=new RectF(w*.035f,h*.025f,w*.965f,h*.125f);p.setColor(Color.argb(185,25,35,50));c.drawRoundRect(hud,w*.03f,w*.03f,p);
        p.setColor(Color.WHITE);p.setTextSize(w*.045f);p.setTextAlign(Paint.Align.LEFT);c.drawText(""+score,w*.075f,h*.088f,p);
        p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*.035f);c.drawText(castleMode?"ЗАМОК":"УР. "+level,w*.50f,h*.064f,p);p.setTextSize(w*.029f);c.drawText("ЭТАЖ "+floorNumber,w*.50f,h*.099f,p);
        p.setTextAlign(Paint.Align.RIGHT);p.setTextSize(w*.038f);c.drawText(String.format("%02d",Math.max(0,levelRemaining)/1000),w*.92f,h*.064f,p);
        float hx=w*.715f;for(int i=0;i<START_LIVES;i++){drawHeart(c,hx+i*w*.045f,h*.092f,w*.018f,i<lives?0xffff5a65:0x554f5968);}p.setTextAlign(Paint.Align.LEFT);
        if(now<slowUntil)drawPowerPill(c,w*.10f,h*.15f,"⏱");if(now<fastUntil)drawPowerPill(c,w*.20f,h*.15f,"⚡");if(now<doubleUntil)drawPowerPill(c,w*.30f,h*.15f,"×2");
        if(now<messageUntil){p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*.044f);p.setShadowLayer(7,0,3,0xaa000000);c.drawText(message,w*.5f,h*.205f,p);p.clearShadowLayer();p.setTextAlign(Paint.Align.LEFT);}
    }

    private void drawPowerPill(Canvas c,float cx,float cy,String txt){p.setColor(Color.argb(180,30,40,60));c.drawRoundRect(new RectF(cx-w*.045f,cy-h*.018f,cx+w*.045f,cy+h*.018f),20,20,p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*.025f);c.drawText(txt,cx,cy+w*.009f,p);p.setTextAlign(Paint.Align.LEFT);}

    private void drawFloorCounter(Canvas c){if(screen!=Screen.PLAYING)return;float frac=Math.min(1f,servedThisFloor/(float)floorGoal());RectF bg=new RectF(w*.33f,h*.792f,w*.67f,h*.808f);p.setColor(Color.argb(110,25,28,40));c.drawRoundRect(bg,10,10,p);p.setColor(Color.rgb(255,201,69));c.drawRoundRect(new RectF(bg.left,bg.top,bg.left+bg.width()*frac,bg.bottom),10,10,p);}

    private void drawMenu(Canvas c) {
        drawCrab(c,w*.5f,h*.50f,SystemClock.uptimeMillis());
        p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setShadowLayer(10,0,5,0x77000000);p.setTextSize(w*.105f);c.drawText("КРАБ",w*.5f,h*.24f,p);p.setTextSize(w*.070f);c.drawText("КУРЬЕР",w*.5f,h*.305f,p);p.clearShadowLayer();
        p.setColor(Color.argb(205,255,194,45));c.drawRoundRect(startButton,w*.05f,w*.05f,p);p.setColor(Color.rgb(70,43,35));p.setTextSize(w*.062f);c.drawText("СТАРТ",startButton.centerX(),startButton.centerY()+w*.021f,p);
        p.setColor(Color.WHITE);p.setTextSize(w*.035f);c.drawText("ЛУЧШИЙ СЧЁТ: "+bestScore,w*.5f,startButton.bottom+h*.055f,p);p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawGameOver(Canvas c) {
        p.setColor(Color.argb(188,17,23,34));c.drawRect(0,0,w,h,p);
        p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setTextSize(w*.085f);c.drawText("БУРГЕРЫ КОНЧИЛИСЬ!",w*.5f,h*.37f,p);p.setTextSize(w*.052f);c.drawText("СЧЁТ: "+score,w*.5f,h*.46f,p);p.setTextSize(w*.035f);c.drawText("ЛУЧШИЙ: "+bestScore,w*.5f,h*.515f,p);
        p.setColor(Color.rgb(255,194,45));c.drawRoundRect(startButton,w*.05f,w*.05f,p);p.setColor(Color.rgb(70,43,35));p.setTextSize(w*.060f);c.drawText("СТАРТ",startButton.centerX(),startButton.centerY()+w*.020f,p);p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawTransition(Canvas c) {
        if(transitionMs<=0)return;float alpha=Math.min(1f,transitionMs/450f);p.setColor(Color.argb((int)(130*alpha),15,18,30));c.drawRect(0,h*.34f,w,h*.56f,p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*.065f);c.drawText(transitionText,w*.5f,h*.465f,p);p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawCloud(Canvas c,float cx,float cy,float size){c.drawCircle(cx-size*.34f,cy,size*.28f,p);c.drawCircle(cx,cy-size*.13f,size*.38f,p);c.drawCircle(cx+size*.36f,cy,size*.26f,p);c.drawRoundRect(new RectF(cx-size*.55f,cy,cx+size*.58f,cy+size*.25f),size*.13f,size*.13f,p);}

    private void drawHeart(Canvas c,float cx,float cy,float r,int color){p.setColor(color);Path path=new Path();path.moveTo(cx,cy+r*1.25f);path.cubicTo(cx-r*1.7f,cy+r*.2f,cx-r*1.15f,cy-r,cx-r*.45f,cy-r*.55f);path.cubicTo(cx,cy-r*1.25f,cx+r*.45f,cy-r*.55f,cx+r*.45f,cy-r*.55f);path.cubicTo(cx+r*1.15f,cy-r,cx+r*1.7f,cy+r*.2f,cx,cy+r*1.25f);path.close();c.drawPath(path,p);}

    @Override public boolean onTouchEvent(MotionEvent e) {
        if(e.getAction()!=MotionEvent.ACTION_DOWN)return true;float x=e.getX(),y=e.getY();long now=SystemClock.uptimeMillis();
        if(screen==Screen.MENU||screen==Screen.GAME_OVER){if(startButton.contains(x,y))startGame();return true;}
        if(transitionMs>0||scrolling)return true;

        for(Bird b:birds){float bx=b.x*w,by=b.y*h;if(dist2(x,y,bx,by)<sq(w*.06f)){b.flap+=3.5f;audio.playTapGag();message="КАР?!";messageUntil=now+700;return true;}}
        RectF plane=new RectF(planeX-w*.03f,planeY-h*.025f,planeX+w*.27f,planeY+h*.07f);if(plane.contains(x,y)){planeGag=true;planeGagUntil=now+2200;audio.playTapGag();return true;}

        if(shot!=null)return true;
        if(zebra!=null&&dist2(x,y,zebra.x,zebra.y)<sq(w*.105f)){launchShot(zebra.x,zebra.y,ShotType.ZEBRA,zebra,now);return true;}
        if(bonus!=null&&dist2(x,y,bonus.x,bonus.y)<sq(w*.085f)){launchShot(bonus.x,bonus.y,ShotType.BONUS,bonus,now);return true;}
        for(Neighbor n:neighbors){RectF r=windowRect(n.slot);RectF touch=new RectF(r.left-r.width()*.06f,r.top-r.height()*.20f,r.right+r.width()*.06f,r.bottom+r.height()*.08f);if(touch.contains(x,y)){launchShot(r.centerX(),r.centerY(),ShotType.NEIGHBOR,n,now);return true;}}
        return true;
    }

    private void vibrate(int ms){try{if(vibrator==null||!vibrator.hasVibrator())return;if(android.os.Build.VERSION.SDK_INT>=26)vibrator.vibrate(VibrationEffect.createOneShot(ms,VibrationEffect.DEFAULT_AMPLITUDE));else vibrator.vibrate(ms);}catch(Exception ignored){}}
    private static float ease(float t){t=Math.max(0,Math.min(1,t));return 1f-(1f-t)*(1f-t)*(1f-t);}
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    private static float dist2(float x1,float y1,float x2,float y2){float dx=x1-x2,dy=y1-y2;return dx*dx+dy*dy;}
    private static float sq(float a){return a*a;}

    private static class Neighbor {int slot,kind;long remaining,total,born,nextRant;boolean warned;Neighbor(int s,long wait,int k,long now){slot=s;remaining=total=wait;kind=k;born=now;}}
    private static class BurgerShot {float sx,sy,tx,ty,progress;ShotType type;Object target;long started,duration;BurgerShot(float a,float b,float c,float d,ShotType t,Object o,long st,long du){sx=a;sy=b;tx=c;ty=d;type=t;target=o;started=st;duration=du;}}
    private static class Bonus {float x,y,phase;long remaining=4800;BonusType type;Bonus(float a,float b,BonusType t){x=a;y=b;type=t;}}
    private static class Zebra {float x,y,phase;long remaining;Zebra(float a,float b,long r){x=a;y=b;remaining=r;}}
    private class EnemyBurger {float sx,sy,tx,ty;long started;EnemyBurger(float a,float b,long st){sx=a;sy=b;started=st;tx=w*(.30f+rng.nextFloat()*.40f);ty=h*(.35f+rng.nextFloat()*.30f);}}
    private static class Splat {float x,y;long age;Splat(float a,float b){x=a;y=b;}}
    private static class Bird {float x,y,speed,flap;Bird(float a,float b){x=a;y=.12f+b*.22f;speed=.025f+(a*.02f);}}
}
