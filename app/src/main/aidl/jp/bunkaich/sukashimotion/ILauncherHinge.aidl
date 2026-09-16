package jp.bunkaich.sukashimotion;
import jp.bunkaich.sukashimotion.IAngleSink;
interface ILauncherHinge {
 boolean registerListener(IAngleSink listener);
 void unregisterListener(IAngleSink listener);
}
