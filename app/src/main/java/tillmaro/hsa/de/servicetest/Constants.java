package tillmaro.hsa.de.servicetest;

public class Constants {
    public interface ACTION {
        public static String START_RECORD = "tillmaro.hsa.de.servicetest.myservice.startRecord";
        public static String STOP_RECORD = "tillmaro.hsa.de.servicetest.myservice.stopRecord";
        public static String START_SERVICE = "tillmaro.hsa.de.servicetest.myservice.startService";
        public static String STOP_SERVICE = "tillmaro.hsa.de.servicetest.myservice.stopService";
    }

    public interface NOTIFICATION_ID {
        public static int FOREGROUND_SERVICE = 101;
    }
}
