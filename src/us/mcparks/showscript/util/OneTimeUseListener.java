package us.mcparks.showscript.util;

import java.util.function.Consumer;
import java.util.function.Predicate;

import us.mcparks.showscript.Main;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

public class OneTimeUseListener {
    public static <T extends Event> void createOneTimeUseListener (Class<? extends Event> event, Predicate<T> filter, Consumer<T> toDo) {
        createOneTimeUseListener(event, filter, toDo, EventPriority.NORMAL);
    }

    public static <T extends Event> void createOneTimeUseListener (Class<? extends Event> event, Predicate<T> filter, Consumer<T> toDo, EventPriority priority) {
        MyListener<T> listener = new MyListener<>();
        Bukkit.getPluginManager().registerEvent(event, listener, priority, (executorListener, executorEvent) -> listener.onEvent(filter, toDo, (T)executorEvent), Main.getPlugin(Main.class));
    }

    static class MyListener<T extends Event> implements Listener {
        public void onEvent(Predicate<T> filter, Consumer<T> toDo, T event) {
            if (filter.test(event)) {
                toDo.accept(event);
                HandlerList.unregisterAll(this);
            }
        } 
    }
}