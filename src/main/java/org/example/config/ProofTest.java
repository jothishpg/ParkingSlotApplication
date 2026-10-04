package org.example.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

public class ProofTest {
    public static void main(String[] args) throws Exception {
        String appSecret = "51ef6bb05898b2f88b1ab60c00d46687";
        String accessToken = "EAAPaRQYlbB8BSvrHid3m21nSyXJZC3CCfZBDPxAvbtU0s2JnZAbjyMIWq4SYzIhWSSrIy2EMgmJDQyDpH86sIFkjuODca9l8WRPNos3GVbgxsMJK5NZAgFcQu91cl9hkqUzxKSQBOfuqVdukPwcws05ozCKrC2J7yPQ3yH5u4C1sVDD4TZBytex0ClhZCtPkAIwGW4PB8Q07Ydm7TnbJuIcFHOiKCn8K4q1BZAE6WbqViS5O02YOwgIZBTBS0mUsTFo4CvFPBUCZAztY0cLMZD";

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(appSecret.getBytes(), "HmacSHA256"));
        byte[] hash = mac.doFinal(accessToken.getBytes());

        System.out.println(HexFormat.of().formatHex(hash));
    }
}