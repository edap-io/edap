package ebc.io.edap.beanconvert.list;

import io.edap.beanconvert.Convertor;
import io.edap.beanconvert.ConvertorRegister;
import io.edap.beanconvert.test.dto.CarDTO;
import io.edap.beanconvert.test.vo.SportCar;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ListConvertor_0 {
    private static final Convertor<SportCar, CarDTO> LIST_CONVERTOR = ConvertorRegister.instance().getConvertor(SportCar.class, CarDTO.class);

    public ListConvertor_0() {
    }

    public static List<CarDTO> convertList(List<SportCar> var0) {
        if (var0 == null) {
            return null;
        } else {
            ArrayList var1 = new ArrayList(var0.size());
            Iterator var2 = var0.iterator();

            while(var2.hasNext()) {
                SportCar var3 = (SportCar)var2.next();
                var1.add(LIST_CONVERTOR.convert(var3));
            }

            return var1;
        }
    }
}