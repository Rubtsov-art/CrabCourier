package com.crabcourier.game;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Iterator;

public class GameView extends View {
    private final Paint p = new Paint(3);
    private float crabX = 220, crabY;
    private int score = 0;
    private final ArrayList<Food> food = new ArrayList<>();
    private final ArrayList<WindowTarget> targets = new ArrayList<>();
    private SecretTarget secret;
    private boolean secretWorld = false;
    private boolean climbing = false;
    private float worldOffset = 0;
    private int secretHits = 0;
    private long secretStarted = 0;

    public GameView(Context c) {
        super(c);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        setBackgroundColor(Color.rgb(120, 200, 245));
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        crabY = h - 145;
        if (!secretWorld) drawCity(c, w, h); else drawSecretWorld(c, w, h);
        drawCrab(c);
        updateAndDrawFood(c, w, h);
        drawHud(c, w, h);
        invalidate();
    }

    private void drawCity(Canvas c, float w, float h) {
        c.drawColor(Color.rgb(120, 200, 245));
        p.setColor(Color.WHITE); c.drawCircle(w * .16f, 90, 42, p); c.drawCircle(w * .20f, 85, 55, p);
        p.setColor(Color.rgb(245,205,140)); c.drawRect(w*.52f,40,w,h-80,p);
        p.setColor(Color.rgb(215,120,90)); c.drawRect(w*.78f,10,w,h-80,p);
        if (targets.isEmpty()) for(int i=0;i<6;i++) targets.add(new WindowTarget(w*.58f+(i%3)*w*.13f,100+(i/3)*170));
        for(WindowTarget t:targets){p.setColor(t.flash>0?Color.YELLOW:Color.rgb(60,105,150));c.drawRect(t.x,t.y,t.x+90,t.y+100,p);if(t.flash>0)t.flash--;}

        // Secret target: an original golden delivery bell hidden on the rooftop.
        if(secret==null) secret=new SecretTarget(w*.86f, 55);
        p.setColor(Color.rgb(90,75,65)); c.drawRect(secret.x-6, secret.y+24, secret.x+46, secret.y+31,p);
        p.setColor(secret.flash>0?Color.WHITE:Color.rgb(255,190,35)); c.drawOval(secret.x,secret.y,secret.x+40,secret.y+30,p);
        p.setColor(Color.rgb(130,85,35)); c.drawCircle(secret.x+20,secret.y+30,5,p);
        if(secret.flash>0) secret.flash--;

        drawStreet(c,w,h);
    }

    private void drawSecretWorld(Canvas c, float w, float h) {
        // Purple night world unlocked by hitting the rooftop bell.
        c.drawColor(Color.rgb(45,30,85));
        p.setColor(Color.rgb(245,225,145)); c.drawCircle(w*.14f,90,48,p);
        p.setColor(Color.rgb(75,55,115));
        for(int i=0;i<8;i++){
            float y=(i*125 + worldOffset)% (h+150)-100;
            c.drawRect(w*.52f,y,w*.94f,y+82,p);
            p.setColor(Color.rgb(120,230,235)); c.drawRect(w*.58f,y+16,w*.65f,y+55,p); c.drawRect(w*.76f,y+16,w*.83f,y+55,p);
            p.setColor(Color.rgb(75,55,115));
        }
        p.setColor(Color.rgb(25,25,45)); c.drawRect(0,h-80,w,h,p);
        p.setColor(Color.rgb(120,230,235)); for(int i=0;i<8;i++) c.drawRect(i*w/8f+20,h-43,i*w/8f+85,h-35,p);

        // Floating targets make the secret area playable.
        for(int i=0;i<3;i++){
            float tx=w*.60f+i*w*.12f, ty=130+i*95;
            p.setColor(Color.rgb(255,120,190)); c.drawCircle(tx,ty,30,p);
            p.setColor(Color.WHITE); c.drawCircle(tx-10,ty-5,5,p); c.drawCircle(tx+10,ty-5,5,p);
        }
        if(climbing){worldOffset+=3.2f; crabY=Math.max(h*.48f, crabY-1.0f);}
    }

    private void drawStreet(Canvas c,float w,float h){
        p.setColor(Color.rgb(70,75,80));c.drawRect(0,h-80,w,h,p);p.setColor(Color.WHITE);for(int i=0;i<8;i++)c.drawRect(i*w/8f+20,h-43,i*w/8f+85,h-35,p);
    }

    private void drawCrab(Canvas c){
        p.setColor(Color.rgb(235,75,55));c.drawOval(crabX-55,crabY-40,crabX+55,crabY+35,p);c.drawCircle(crabX-35,crabY-48,16,p);c.drawCircle(crabX+35,crabY-48,16,p);
        p.setColor(Color.WHITE);c.drawCircle(crabX-35,crabY-50,8,p);c.drawCircle(crabX+35,crabY-50,8,p);p.setColor(Color.BLACK);c.drawCircle(crabX-33,crabY-50,4,p);c.drawCircle(crabX+37,crabY-50,4,p);
        p.setStrokeWidth(12);p.setColor(Color.rgb(235,75,55));c.drawLine(crabX-50,crabY,crabX-85,crabY-35,p);c.drawLine(crabX+50,crabY,crabX+85,crabY-35,p);
    }

    private void updateAndDrawFood(Canvas c,float w,float h){
        Iterator<Food> it=food.iterator();
        while(it.hasNext()){
            Food f=it.next(); f.x+=f.vx; f.y+=f.vy; f.vy+=.35f;
            p.setColor(Color.rgb(225,165,70));c.drawOval(f.x-18,f.y-12,f.x+18,f.y+12,p);p.setColor(Color.rgb(80,160,70));c.drawRect(f.x-16,f.y-2,f.x+16,f.y+3,p);
            boolean hit=false;
            if(!secretWorld){
                for(WindowTarget t:targets) if(f.x>t.x&&f.x<t.x+90&&f.y>t.y&&f.y<t.y+100){score+=10;t.flash=8;hit=true;break;}
                if(!hit && secret!=null && f.x>secret.x-8&&f.x<secret.x+50&&f.y>secret.y-8&&f.y<secret.y+42){
                    secret.flash=10; score+=67; hit=true; secretWorld=true; climbing=true; secretStarted=System.currentTimeMillis(); food.clear(); break;
                }
            } else {
                // Hit glowing orbs for bonus points in the secret world.
                for(int i=0;i<3;i++){
                    float tx=w*.60f+i*w*.12f, ty=130+i*95;
                    float dx=f.x-tx, dy=f.y-ty;
                    if(dx*dx+dy*dy<38*38){score+=25;secretHits++;hit=true;break;}
                }
            }
            if(hit||f.x>w+30||f.y>h||f.y<-30)it.remove();
        }
    }

    private void drawHud(Canvas c,float w,float h){
        p.setColor(Color.WHITE);p.setTextSize(38);c.drawText("КРАБ-КУРЬЕР",25,45,p);p.setTextSize(30);c.drawText("Очки: "+score,25,85,p);
        if(!secretWorld){c.drawText("Коснись цели — бросить бургер",25,120,p);p.setTextSize(20);c.drawText("Где-то наверху спрятан секрет...",25,150,p);}
        else {
            p.setTextSize(29);c.drawText("СЕКРЕТНЫЙ МИР! Поднимаемся выше!",25,122,p);
            p.setTextSize(23);c.drawText("Попади в светящиеся сферы: "+secretHits,25,154,p);
            if(System.currentTimeMillis()-secretStarted<2600){p.setTextSize(54);c.drawText("СЕКРЕТ ОТКРЫТ! +67",w*.30f,h*.28f,p);}
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_DOWN){
            float dx=e.getX()-crabX,dy=e.getY()-crabY;float d=(float)Math.sqrt(dx*dx+dy*dy);if(d<1)d=1;float speed=15;food.add(new Food(crabX+60,crabY-25,dx/d*speed,dy/d*speed));return true;
        }return true;
    }
    static class Food{float x,y,vx,vy;Food(float a,float b,float c,float d){x=a;y=b;vx=c;vy=d;}}
    static class WindowTarget{float x,y;int flash=0;WindowTarget(float a,float b){x=a;y=b;}}
    static class SecretTarget{float x,y;int flash=0;SecretTarget(float a,float b){x=a;y=b;}}
}
