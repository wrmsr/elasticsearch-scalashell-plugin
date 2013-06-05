package com.yelp.elasticsearch.plugins.scalashell;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.AbstractPlugin;
import peak6.util.ScalaSshShell;
import scala.None$;
import scala.Option;

public class ScalaShellPlugin extends AbstractPlugin {

    public ScalaShellPlugin(Settings settings) {
        start();
    }

    @Override
    public String name() {
        return "scalashell";
    }

    @Override
    public String description() {
        return "Scala Shell plugin";
    }

    public static class ScalaLang {

        public static <T> Option<T> none() {
            return (Option<T>) None$.MODULE$;
        }
    }

    public void start() {
        ScalaSshShell sshd = new ScalaSshShell(
                4444, "test", "user", "fluke", ScalaLang.<String>none());
        sshd.start();
    }
}
