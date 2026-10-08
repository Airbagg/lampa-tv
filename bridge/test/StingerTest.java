import local.vlcbridge.StingerFinder;

import java.util.ArrayList;
import java.util.List;

/** Проверка StingerFinder.find на времени реплик как в «Нет пути домой» (длительность 8891 с). */
public class StingerTest {
    static double[] track(double[][] spans) {
        List<Double> t = new ArrayList<>();
        for (double[] s : spans) for (double x = s[0]; x <= s[1]; x += 4) t.add(x);
        double[] a = new double[t.size()];
        for (int i = 0; i < a.length; i++) a[i] = t.get(i);
        return a;
    }

    public static void main(String[] args) {
        double dur = 8891;
        List<double[]> nwh = new ArrayList<>();
        // Пауза 7516–7605 есть у всех (тихая сцена фильма, 89 с — не титры)
        nwh.add(track(new double[][]{{0, 7516}, {7605, 8042}, {8231, 8389}, {8757, 8879}}));      // rus full
        nwh.add(track(new double[][]{{0, 7516}, {7605, 8056}, {8240, 8332}, {8762, 8880}}));      // rus FOCS
        nwh.add(track(new double[][]{{0, 7516}, {7605, 8052}, {8231, 8388}, {8762, 8884}}));      // ukr
        nwh.add(track(new double[][]{{0, 7516}, {7605, 7857}, {7924, 8328}, {8762, 8871}}));      // eng (поёт в титрах)
        System.out.println("No Way Home: " + StingerFinder.find(nwh, dur));

        // Ложная тревога: 3 минуты боя без реплик за 15 минут до конца, потом 12 минут разговоров
        List<double[]> fake = new ArrayList<>();
        fake.add(track(new double[][]{{0, 7900}, {8080, 8800}}));
        fake.add(track(new double[][]{{0, 7900}, {8080, 8800}}));
        System.out.println("Silent fight: " + StingerFinder.find(fake, dur));

        // Фильм без сцены после титров: реплики кончились, титры до конца
        List<double[]> none = new ArrayList<>();
        none.add(track(new double[][]{{0, 8500}}));
        System.out.println("No stinger: " + StingerFinder.find(none, dur));
    }
}
