package it.lorenzogiorgi.tesi.utiliy;

import java.util.Random;

public class TokenUtiliy {
    /**
     * Generate a random token and encode it using hexadecimal characters
     * @param byteLength number of random byte to be used. Since each hex character encode 4 bits, the number of
     *                   characters will the double of this parameter.
     * @return random string encoded using hexadecimal characters
     */
    public static String generateRandomHexString(int byteLength) {
        byte[] array = new byte[byteLength];
        new Random().nextBytes(array);
        return byteToHexString(array);
    }

    public static String byteToHexString(byte[] bytes) {
        char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j*2] = hexArray[v/16];
            hexChars[j*2 + 1] = hexArray[v%16];
        }
        return new String(hexChars);
    }

}
