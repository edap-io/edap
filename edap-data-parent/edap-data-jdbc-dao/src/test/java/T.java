import io.edap.util.CollectionUtils;
import io.edap.util.Constants;

import java.util.List;
import java.util.Random;

public class T {

    final static char[] digits = {
            '0' , '1' , '2' , '3' , '4' , '5' ,
            '6' , '7' , '8' , '9' , 'a' , 'b' ,
            'c' , 'd' , 'e' , 'f' , 'g' , 'h' ,
            'i' , 'j' , 'k' , 'l' , 'm' , 'n' ,
            'o' , 'p' , 'q' , 'r' , 's' , 't' ,
            'u' , 'v' , 'w' , 'x' , 'y' , 'z'
    };

    public static void main(String[] args) {
        List empty = Constants.EMPTY_LIST;
        empty.add("1");
        byte[] bs = new byte[20];
        for (int i=0;i<20;i++) {
            bs[i] = (byte)(new Random().nextInt(256) - 128);
        }
        System.out.println(conver2HexStr(bs));
    }

    public static String conver2HexStr(byte[] bs) {
        if (bs == null || bs.length == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < bs.length; i++) {
            byte b = bs[i];
            result.append(digits[(b&0xFF)>>4]);
            result.append(digits[b&0x0F]);
        }
        return result.toString();
    }
}
