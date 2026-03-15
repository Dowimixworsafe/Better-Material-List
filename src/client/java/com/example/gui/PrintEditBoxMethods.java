import java.lang.reflect.Method;
import net.minecraft.client.gui.components.EditBox;

public class PrintEditBoxMethods {
    public static void main(String[] args) {
        try {
            for (Method m : EditBox.class.getMethods()) {
                if (m.getName().equals("keyPressed") || m.getName().equals("charTyped") || m.getName().equals("onKeyTyped")) {
                    System.out.println(m);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
