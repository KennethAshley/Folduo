package jp.bunkaich.sukashimotion;
import jp.bunkaich.sukashimotion.IAngleSink;
import android.os.Bundle;
interface ILauncherHinge {
 boolean registerListener(IAngleSink listener);
 void unregisterListener(IAngleSink listener);
 // Display methods additionally require the exact paired DuoFold package.
 Bundle startSmoothHome(IAngleSink listener);
 Bundle renewSmoothHome(IAngleSink listener);
 void stopSmoothHome(IAngleSink listener);
}
