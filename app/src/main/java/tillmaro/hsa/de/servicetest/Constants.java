package tillmaro.hsa.de.servicetest;

public class Constants {
    public interface ACTION {
        String START_RECORD = "tillmaro.hsa.de.servicetest.myservice.startRecord";
        String STOP_RECORD = "tillmaro.hsa.de.servicetest.myservice.stopRecord";
        String START_SERVICE = "tillmaro.hsa.de.servicetest.myservice.startService";
        String STOP_SERVICE = "tillmaro.hsa.de.servicetest.myservice.stopService";
    }

    public interface NOTIFICATION_ID {
        int FOREGROUND_SERVICE = 101;
    }
}
