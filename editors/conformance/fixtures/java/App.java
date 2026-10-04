// Fixture for the Blackbox conformance suite (editors/conformance/test).
// The tests refer to these line numbers; keep them in step with app.go.

public class App {
    static int add(int a, int b) {
        return a + b;
    }

    public static void main(String[] args) {
        int total = 0;
        for (int i = 1; i <= 2; i++) {
            // Same lines as app.go: breakpoint on 14, logpoint on 15.

            total = add(total, i); // line 14: breakpoint
            System.out.println("total " + total); // line 15: logpoint
        }
        System.out.println("done " + total);
    }
}
