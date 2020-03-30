package io.enmasse.test;

import java.util.concurrent.atomic.AtomicLong;

public class LinkInfo {

	private AtomicLong msgs = new AtomicLong(0);
	private boolean detached = false;
	private int credits = 0;
	private int queued = 0;

	public AtomicLong getMsgs() {
		return msgs;
	}

	public boolean isDetached() {
		return detached;
	}

	public void setDetached(boolean detached) {
		this.detached = detached;
	}

	public int getCredits() {
		return credits;
	}

	public void setCredits(int credits) {
		this.credits = credits;
	}

	public int getQueued() {
		return queued;
	}

	public void setQueued(int queued) {
		this.queued = queued;
	}

	@Override
	public String toString() {
		var b = new StringBuilder().append("[ ");
		if (detached) {
			b.append("detached=").append(detached).append(", ");
		}
		b.append("messages=").append(msgs.get()).append(", ");
		b.append("credits=").append(credits).append(", ");
		b.append("queued=").append(queued);
		return b.append(" ]").toString();
	}

}
