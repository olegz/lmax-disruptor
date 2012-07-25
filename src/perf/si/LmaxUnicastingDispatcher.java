package si;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.integration.Message;
import org.springframework.integration.MessageDeliveryException;
import org.springframework.integration.MessageDispatchingException;
import org.springframework.integration.MessagingException;
import org.springframework.integration.core.MessageHandler;
import org.springframework.integration.dispatcher.AbstractDispatcher;
import org.springframework.integration.dispatcher.AggregateMessageDeliveryException;
import org.springframework.integration.dispatcher.LoadBalancingStrategy;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.PreallocatedRingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.Sequencer;
import com.lmax.disruptor.SingleProducerSequencer;
import com.lmax.disruptor.YieldingWaitStrategy;

public class LmaxUnicastingDispatcher extends AbstractDispatcher implements InitializingBean {

	private volatile boolean failover = true;
	private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
	private volatile LoadBalancingStrategy loadBalancingStrategy;

	private final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

	private final PreallocatedRingBuffer<MessageEvent> ringBuffer =
	        new PreallocatedRingBuffer<MessageEvent>(MessageEvent.EVENT_FACTORY,
	                new SingleProducerSequencer(8, new YieldingWaitStrategy()));

	private final SequenceBarrier sequenceBarrier = ringBuffer.newBarrier();
	private final MessageEventHandler handler = new MessageEventHandler();
	private final BatchEventProcessor<MessageEvent> batchEventProcessor =
			new BatchEventProcessor<MessageEvent>(ringBuffer, sequenceBarrier, handler);


	public LmaxUnicastingDispatcher() {
		ringBuffer.setGatingSequences(batchEventProcessor.getSequence());
	}

	/**
	 * Provide a {@link LoadBalancingStrategy} for this dispatcher.
	 */
	public void setLoadBalancingStrategy(LoadBalancingStrategy loadBalancingStrategy) {
		Lock lock = rwLock.writeLock();
		lock.lock();
		try {
			this.loadBalancingStrategy = loadBalancingStrategy;
		}
		finally {
			lock.unlock();
		}
	}

	@Override
	public boolean dispatch(final Message<?> message) {
		Sequencer sequencer = ringBuffer.getSequencer();
		long nextSequence = sequencer.next();
		ringBuffer.getPreallocated(nextSequence).setMessage(message);
		sequencer.publish(nextSequence);
		//ringBuffer.get(nextSequence).setMessage(message);
		//ringBuffer.publish(nextSequence);
		return true;
	}


	public boolean doDispatch(final Message<?> message) {
		boolean success = false;
		Iterator<MessageHandler> handlerIterator = this.getHandlerIterator(message);
		if (!handlerIterator.hasNext()) {
			throw new MessageDispatchingException(message, "Dispatcher has no subscribers");
		}
		List<RuntimeException> exceptions = new ArrayList<RuntimeException>();
		while (success == false && handlerIterator.hasNext()) {
			MessageHandler handler = handlerIterator.next();
			try {
				handler.handleMessage(message);
				success = true; // we have a winner.
			}
			catch (Exception e) {
				RuntimeException runtimeException = (e instanceof RuntimeException)
						? (RuntimeException) e
						: new MessageDeliveryException(message,
								"Dispatcher failed to deliver Message.", e);
				if (e instanceof MessagingException &&
						((MessagingException) e).getFailedMessage() == null) {
					((MessagingException) e).setFailedMessage(message);
				}
				exceptions.add(runtimeException);
				this.handleExceptions(exceptions, message, !handlerIterator.hasNext());
			}
		}
		return success;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		EXECUTOR.submit(batchEventProcessor);
	}

	/**
	 * Returns the iterator that will be used to loop over the handlers.
	 * Delegates to a {@link LoadBalancingStrategy} if available. Otherwise,
	 * it simply returns the Iterator for the existing handler List.
	 */
	private Iterator<MessageHandler> getHandlerIterator(Message<?> message) {
		Lock lock = rwLock.readLock();
		lock.lock();
		try {
			if (this.loadBalancingStrategy != null) {
				return this.loadBalancingStrategy.getHandlerIterator(message, this.getHandlers());
			}
		} finally {
			lock.unlock();
		}
		return this.getHandlers().iterator();
	}

	private void handleExceptions(List<RuntimeException> allExceptions, Message<?> message, boolean isLast) {
		if (isLast || !this.failover) {
			if (allExceptions != null && allExceptions.size() == 1) {
				throw allExceptions.get(0);
			}
			throw new AggregateMessageDeliveryException(message,
					"All attempts to deliver Message to MessageHandlers failed.", allExceptions);
		}
	}

	static class MessageEvent
	{
	    private Message<?> value;

	    public Message<?> getMessage()
	    {
	        return value;
	    }

	    public void setMessage(final Message<?> value)
	    {
	        this.value = value;
	    }

	    public final static EventFactory<MessageEvent> EVENT_FACTORY = new EventFactory<MessageEvent>()
	    {
	        @Override
			public MessageEvent newInstance()
	        {
	            return new MessageEvent();
	        }
	    };
	}

	private  class MessageEventHandler implements EventHandler<MessageEvent> {

		@Override
		public void onEvent(MessageEvent event, long sequence,
				boolean endOfBatch) throws Exception {
			//System.out.println("LMAX Handling: " + event.getMessage());
			doDispatch(event.getMessage());
		}

	}

}
