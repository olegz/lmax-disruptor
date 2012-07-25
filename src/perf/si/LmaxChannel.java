package si;

import org.springframework.integration.channel.AbstractSubscribableChannel;
import org.springframework.integration.dispatcher.MessageDispatcher;

public class LmaxChannel extends AbstractSubscribableChannel{

	final LmaxUnicastingDispatcher dispatcher;


	public LmaxChannel(LmaxUnicastingDispatcher dispatcher){
		this.dispatcher = dispatcher;
	}

	@Override
	protected MessageDispatcher getDispatcher() {
		return this.dispatcher;
	}
}
