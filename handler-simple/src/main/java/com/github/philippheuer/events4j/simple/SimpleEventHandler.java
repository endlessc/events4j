package com.github.philippheuer.events4j.simple;

import com.github.philippheuer.events4j.api.domain.IDisposable;
import com.github.philippheuer.events4j.api.service.IEventHandler;
import com.github.philippheuer.events4j.simple.domain.EventSubscriber;
import com.github.philippheuer.events4j.simple.domain.SimpleEventHandlerSubscription;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class SimpleEventHandler implements IEventHandler {

    /**
     * Consumer based handlers
     */
    @Getter
    @SuppressWarnings("rawtypes")
    private final ConcurrentMap<Class<?>, List<Consumer>> consumerBasedHandlers = new ConcurrentHashMap<>();

    /**
     * Annotation based method listeners
     */
    private final ConcurrentMap<Class<?>, ConcurrentMap<Method, List<Object>>> methodListeners = new ConcurrentHashMap<>();

    /**
     * Registers a listener using {@link EventSubscriber} method annotations.
     *
     * @param eventListener The class instance annotated with {@link EventSubscriber} annotations.
     */
    public void registerListener(Object eventListener) {
        registerListener(eventListener.getClass(), eventListener);
    }

    /**
     * Registers a new consumer based event handler
     *
     * @param eventClass the event class to obtain events from
     * @param consumer   the event consumer / handler method
     * @param <E>        the event type
     * @return a new Disposable of the given eventType
     */
    public <E> IDisposable onEvent(Class<E> eventClass, Consumer<E> consumer) {
        // register
        @SuppressWarnings("rawtypes")
        final List<Consumer> eventHandlers = consumerBasedHandlers.computeIfAbsent(eventClass, s -> new CopyOnWriteArrayList<>());
        eventHandlers.add(consumer);

        // return disposable
        return new SimpleEventHandlerSubscription(this, eventClass, consumer);
    }

    /**
     * Registers a new event listener.
     *
     * @param eventListenerClass The class of the listener.
     * @param eventListener      The class instance of the listener.
     */
    private void registerListener(Class<?> eventListenerClass, Object eventListener) {
        // for each method on the event listener class
        for (Method method : eventListenerClass.getMethods()) {
            // event subscribers need exactly one parameter
            if (method.getParameterCount() == 1) {
                // event subscriber methods need to be annotated with @EventSubscriber
                if (method.isAnnotationPresent(EventSubscriber.class)) {
                    // ignore access checks so that we can call private methods
                    method.setAccessible(true);

                    // get event class the listener expects
                    Class<?> eventClass = method.getParameterTypes()[0];

                    // check if the event class extends the base event class
                    if (Object.class.isAssignableFrom(eventClass)) {
                        methodListeners.computeIfAbsent(eventClass, c -> new ConcurrentHashMap<>()) // add class to methodListeners
                            .computeIfAbsent(method, m -> new CopyOnWriteArrayList<>()) // add method to methodListeners
                            .add(eventListener); // add event listener to method

                        // log
                        log.info("Registered method listener {}#{}", eventListenerClass.getSimpleName(), method.getName());
                    }
                }
            }
        }
    }

    /**
     * Dispatched a event to the simple based method listeners.
     *
     * @param event The event that will be dispatched to the simple based method listeners.
     */
    public void publish(Object event) {
        // consumer based handlers
        final List<Consumer> eventConsumers = consumerBasedHandlers.get(event.getClass());
        if (eventConsumers != null)
            eventConsumers.forEach(consumer -> consumer.accept(event));

        // annotation handlers
        if (methodListeners.size() > 0) {
            for (Map.Entry<Class<? extends Object>, ConcurrentMap<Method, List<Object>>> e : methodListeners.entrySet()) {
                if (e.getKey().isAssignableFrom(event.getClass())) {
                    ConcurrentMap<Method, List<Object>> eventClass = e.getValue();
                    eventClass.forEach((k, v) -> {
                        v.forEach(object -> {
                            try {
                                // Invoke Event
                                k.invoke(object, event);
                            } catch (IllegalAccessException ex) {
                                log.error("Error dispatching event {}.", event.getClass().getSimpleName());
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                log.error("Unhandled exception caught dispatching event {}.", event.getClass().getSimpleName());
                            }
                        });
                    });
                }
            }
        }
    }

    /**
     * Shutdown
     */
    public void close() {
        // nothing
    }
}
