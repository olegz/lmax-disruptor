package si;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.integration.MessageHeaders.IdGenerator;
import org.springframework.util.StopWatch;

public class LmaxChannelDemo {

	private static Executor executor = Executors.newFixedThreadPool(2);

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception{
		ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("regular-config.xml", LmaxChannelDemo.class);
		MyGateway gateway = context.getBean(MyGateway.class);
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		for (int i = 0; i < 1000000; i++) {
			gateway.process("");
		}
		stopWatch.stop();
		System.out.println("Sent 1000000 messages via SI Queue Channel " + stopWatch.getTotalTimeMillis() + " millis");
		context.stop();

		context = new ClassPathXmlApplicationContext("lmax-config.xml", LmaxChannelDemo.class);
		gateway = context.getBean(MyGateway.class);
		stopWatch = new StopWatch();
		stopWatch.start();
		for (int i = 0; i < 1000000; i++) {
			gateway.process("");
		}
		stopWatch.stop();
		System.out.println("Sent 1000000 messages via LMAX Channel " + stopWatch.getTotalTimeMillis() + " millis");
		context.stop();
	}

	public static interface MyGateway{
		public void process(String value);
	}

	public static class MyService {
		public void process(String value){
			//new Exception().printStackTrace(); // each handler invocation is a new stack
		}
	}

	public static class SampleIdGenerator implements IdGenerator {
		@Override
		public UUID generateId() {
			return UUID.nameUUIDFromBytes(((System.currentTimeMillis() - System.nanoTime()) + "").getBytes());
		}
	}

}
