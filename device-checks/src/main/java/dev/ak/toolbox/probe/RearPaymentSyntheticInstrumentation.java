package dev.ak.toolbox.probe;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

/** Runs only against the locally generated, non-payment secure test application. */
public final class RearPaymentSyntheticInstrumentation extends Instrumentation {
    private int uid;
    private int duration;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        uid = Integer.parseInt(args.getString("uid", "-1"));
        duration = Math.max(3000, Math.min(120000, Integer.parseInt(args.getString("duration", "120000"))));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            int expected = getContext().getPackageManager().getPackageUid("dev.ak.secureprobe", 0);
            if (uid != expected) throw new IllegalArgumentException("不是合成测试应用的 UID");
            Class<?> session = target.getClassLoader().loadClass("dev.ak.toolbox.payment.PaymentSession");
            Class<?> harness = target.getClassLoader().loadClass("dev.ak.toolbox.payment.PaymentTestHarness");
            harness.getMethod("start", Context.class, int.class).invoke(null, target, uid);
            Thread.sleep(duration);
            session.getMethod("stopAll").invoke(null);
            result.putString("result", "synthetic session ended");
        } catch (Throwable error) { result.putString("error", error.getClass().getSimpleName()); }
        finish(0, result);
    }
}
