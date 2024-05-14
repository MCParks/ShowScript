package us.mcparks.showscript.util;

public class Lag
implements Runnable
{
public static int TICK_COUNT = 0;
public static long[] TICKS = new long[600];
public static long LAST_TICK = 0L;


public static double getTPS(int ticks)
{
  if (TICK_COUNT < ticks) {
    return 20.0D;
  }
  int target = (TICK_COUNT - 1 - ticks) % TICKS.length;
  long elapsed = System.currentTimeMillis() - TICKS[target];
  
  return ticks / (elapsed / 1000.0D);
}

public static long getElapsed(int tickID)
{
 


  long time = TICKS[(tickID % TICKS.length)];
  return System.currentTimeMillis() - time;
}

public void run()
{
  TICKS[(TICK_COUNT % TICKS.length)] = System.currentTimeMillis();
  
  TICK_COUNT += 1;
}

public static double getTPS() {
	// TODO Auto-generated method stub
	return getTPS(100);
}
}
