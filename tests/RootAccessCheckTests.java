import dev.ak.toolbox.core.RootAccessCheck;

public final class RootAccessCheckTests {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            switch (args[0]) {
                case "root" -> System.out.println("0");
                case "nonroot" -> System.out.println("2000");
                case "denied" -> { System.out.println("0"); System.exit(1); }
                case "hang" -> Thread.sleep(5000);
                case "empty" -> { }
                default -> throw new IllegalArgumentException();
            }
            return;
        }
        String javaPath = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        String cp = System.getProperty("java.class.path");
        require(RootAccessCheck.check(new String[] {javaPath, "-cp", cp, "RootAccessCheckTests", "root"}, 3000)
            == RootAccessCheck.Result.GRANTED, "root uid not recognized");
        require(RootAccessCheck.check(new String[] {javaPath, "-cp", cp, "RootAccessCheckTests", "nonroot"}, 3000)
            == RootAccessCheck.Result.NOT_GRANTED, "non-root uid accepted");
        require(RootAccessCheck.check(new String[] {javaPath, "-cp", cp, "RootAccessCheckTests", "denied"}, 3000)
            == RootAccessCheck.Result.NOT_GRANTED, "failed command accepted");
        require(RootAccessCheck.check(new String[] {javaPath, "-cp", cp, "RootAccessCheckTests", "empty"}, 3000)
            == RootAccessCheck.Result.UNKNOWN, "empty output treated as definitive");
        require(RootAccessCheck.check(new String[] {javaPath, "-cp", cp, "RootAccessCheckTests", "hang"}, 200)
            == RootAccessCheck.Result.UNKNOWN, "timeout treated as definitive");
        require(RootAccessCheck.check(new String[] {"ak_nonexistent_root_binary_6274"}, 3000)
            == RootAccessCheck.Result.NOT_GRANTED, "missing root binary accepted");
        System.out.println("Root permission success, denial and timeout checks passed.");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
