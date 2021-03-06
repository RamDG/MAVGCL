/****************************************************************************
 *
 *   Copyright (c) 2017 Eike Mansfeld ecm@gmx.de. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 ****************************************************************************/

package com.comino.jmavlib.extensions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.drton.jmavlib.log.FormatErrorException;
import me.drton.jmavlib.log.ulog.FieldFormat;
import me.drton.jmavlib.log.ulog.MessageAddLogged;
import me.drton.jmavlib.log.ulog.MessageData;
import me.drton.jmavlib.log.ulog.MessageDropout;
import me.drton.jmavlib.log.ulog.MessageFormat;
import me.drton.jmavlib.log.ulog.MessageInfo;
import me.drton.jmavlib.log.ulog.MessageLog;
import me.drton.jmavlib.log.ulog.MessageParameter;

public class UlogMAVLinkParser {

	private static final byte MESSAGE_TYPE_FORMAT = (byte) 'F';
	private static final byte MESSAGE_TYPE_DATA = (byte) 'D';
	private static final byte MESSAGE_TYPE_INFO = (byte) 'I';
	private static final byte MESSAGE_TYPE_PARAMETER = (byte) 'P';
	private static final byte MESSAGE_TYPE_ADD_LOGGED_MSG = (byte) 'A';
	private static final byte MESSAGE_TYPE_REMOVE_LOGGED_MSG = (byte) 'R';
	private static final byte MESSAGE_TYPE_SYNC = (byte) 'S';
	private static final byte MESSAGE_TYPE_DROPOUT = (byte) 'O';
	private static final byte MESSAGE_TYPE_LOG = (byte) 'L';

	private ByteBuffer buffer = null;
	private long logStartTimestamp;

	// Header maps
	private Map<String, MessageFormat> messageFormats = new HashMap<String, MessageFormat>();
	private Map<String, Object> parameters = new HashMap<String, Object>();
	private Map<String, List<ParamUpdate>> parameterUpdates = new HashMap<String, List<ParamUpdate>>();
	private List<Subscription> messageSubscriptions = new ArrayList<Subscription>();
	private Map<String, String> fieldsList = new HashMap<String, String>();

	// Data map

	private Map<String, Object> data = new HashMap<String, Object>();

	// System info
	private String systemName;
	private String hw_version;
	private String sw_version;
	private long utcTimeReference;

	// Helpers
	private boolean nestedParsingDone = false;

	private long timeStart=-1;

	public UlogMAVLinkParser() {
		buffer = ByteBuffer.allocate(32768);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.flip();
	}

	public void addToBuffer(int[] data, int len, int offset, boolean sequence_ok) {
		if (sequence_ok) {
			for (int i = 0; i < len; i++)
				buffer.put((byte) (data[i] & 0x00FF));
		} else {
			buffer.clear();
			for (int i = offset; i < len; i++)
				buffer.put((byte) (data[i] & 0x00FF));
		}
	}

	public Map<String, String> getFieldList() {
		return fieldsList;
	}

	public Map<String, Object> getDataBuffer() {
		return data;
	}

	public void reset() {
		messageFormats.clear();
		parameters.clear();
		parameterUpdates.clear();
		messageSubscriptions.clear();
		fieldsList.clear();
		data.clear();

		nestedParsingDone = false;
		buffer.clear();
	}

	public String getSystemInfo() {
		return "Sys:"+systemName+" HWVer:"+hw_version+" SWVer:"+sw_version+" UTCref:"+utcTimeReference;
	}

	public boolean checkHeader() {
		buffer.flip();
		if (!checkMagicHeader()) {
			buffer.clear();
			return false;
		}
		System.out.println("ULOG Logging started at: " + logStartTimestamp);
		buffer.compact();
		logStartTimestamp = 0;
		return true;
	}

	public void parseData() {
		Object msg = null;
		buffer.flip();
		while ((msg = readMessage()) != null) {
			if(msg instanceof MessageData) {
				if (timeStart < 0)
					timeStart = ((MessageData)msg).timestamp;
				applyMsg(data, (MessageData) msg);
			}
		}
		buffer.compact();
	}

	public void parseHeader()   {
		Object msg = null;  long lastTime = -1;
		buffer.flip();
		while ((msg = readMessage()) != null) {

			if (msg instanceof MessageFormat) {
				MessageFormat msgFormat = (MessageFormat) msg;
				messageFormats.put(msgFormat.name, msgFormat);

			} else if (msg instanceof MessageAddLogged) {
				//from now on we cannot have any new MessageFormat's, so we
				//can parse the nested types
				if (!nestedParsingDone) {
					for (MessageFormat m : messageFormats.values()) {
						m.parseNestedTypes(messageFormats);
					}
					//now do a 2. pass to remove the last padding field
					for (MessageFormat m : messageFormats.values()) {
						m.removeLastPaddingField();
					}
					nestedParsingDone = true;
				}
				MessageAddLogged msgAddLogged = (MessageAddLogged) msg;
				MessageFormat msgFormat = messageFormats.get(msgAddLogged.name);
				if(msgFormat == null) {
					System.err.println("Format of subscribed message not found: " + msgAddLogged.name);
					continue;
				}
				Subscription subscription = new Subscription(msgFormat, msgAddLogged.multiID);
				if (msgAddLogged.msgID < messageSubscriptions.size()) {
					messageSubscriptions.set(msgAddLogged.msgID, subscription);
				} else {
					while (msgAddLogged.msgID > messageSubscriptions.size())
						messageSubscriptions.add(null);
					messageSubscriptions.add(subscription);
				}
				if (msgAddLogged.multiID > msgFormat.maxMultiID)
					msgFormat.maxMultiID = msgAddLogged.multiID;


			} else if (msg instanceof MessageParameter) {
				MessageParameter msgParam = (MessageParameter) msg;
				lastTime = System.currentTimeMillis();
				if (parameters.containsKey(msgParam.getKey())) {
					System.out.println("Update to parameter: " + msgParam.getKey() + " value: " + msgParam.value + " at t = " + lastTime);
					// maintain a record of parameters which change during flight
					if (parameterUpdates.containsKey(msgParam.getKey())) {
						parameterUpdates.get(msgParam.getKey()).add(new ParamUpdate(msgParam.getKey(), msgParam.value, lastTime));
					} else {
						List<ParamUpdate> updateList = new ArrayList<ParamUpdate>();
						updateList.add(new ParamUpdate(msgParam.getKey(), msgParam.value, lastTime));
						parameterUpdates.put(msgParam.getKey(), updateList);
					}
				} else {
					// add parameter to the parameters Map
					parameters.put(msgParam.getKey(), msgParam.value);
				}

			} else if (msg instanceof MessageInfo) {
				MessageInfo msgInfo = (MessageInfo) msg;
				if ("sys_name".equals(msgInfo.getKey())) {
					systemName = (String) msgInfo.value;
				} else if ("ver_hw".equals(msgInfo.getKey())) {
					hw_version = (String) msgInfo.value;
				} else if ("ver_sw".equals(msgInfo.getKey())) {
					sw_version = (String) msgInfo.value;
				} else if ("time_ref_utc".equals(msgInfo.getKey())) {
					utcTimeReference = ((long) ((Number) msgInfo.value).intValue()) * 1000 * 1000;
				}

			}
			timeStart=-1;
		}

		buffer.compact();

	}

	public void buildSubscriptions() {
		for (int k = 0; k < messageSubscriptions.size(); ++k) {
			Subscription s = messageSubscriptions.get(k);
			if (s != null) {
				MessageFormat msgFormat = s.format;
				if (msgFormat.name.charAt(0) != '_') {
					int maxInstance = msgFormat.maxMultiID;
					for (int i = 0; i < msgFormat.fields.size(); i++) {
						FieldFormat fieldDescr = msgFormat.fields.get(i);
						if (!fieldDescr.name.contains("_padding") && fieldDescr.name != "timestamp") {
							for (int mid = 0; mid <= maxInstance; mid++) {
								if (fieldDescr.isArray()) {
									for (int j = 0; j < fieldDescr.size; j++) {
										fieldsList.put(msgFormat.name + "_" + mid + "." + fieldDescr.name + "[" + j + "]", fieldDescr.type);
									}
								} else {
									fieldsList.put(msgFormat.name + "_" + mid + "." + fieldDescr.name, fieldDescr.type);
								}
							}
						}
					}
				}
			}
		}
	}

	public Object readMessage()  {

		int s1 = buffer.get() & 0xFF;
		int s2 = buffer.get() & 0xFF;
		int msgSize = s1 + (256 * s2);
		int msgType = buffer.get() & 0xFF;

		if (msgSize > buffer.remaining()-3) {
			buffer.position(buffer.position()-3);
			return null;
		}
		switch (msgType) {
		case MESSAGE_TYPE_DATA:

			s1 = buffer.get() & 0xFF;
			s2 = buffer.get() & 0xFF;
			int msgID = s1 + (256 * s2);
			Subscription subscription = null;
			if (msgID < messageSubscriptions.size())
				subscription = messageSubscriptions.get(msgID);
			if (subscription == null) {
				System.err.println("Unknown DATA subscription ID: " + msgID);
				return new DummyMessage(buffer,msgSize,2);
			}
			try {
				return new MessageData(subscription.format, buffer, subscription.multiID);
			} catch (FormatErrorException e) {
				System.err.println(e.getMessage()+": " + msgID);
				return new DummyMessage(buffer,msgSize,2);
			}
		case MESSAGE_TYPE_INFO:
			return new MessageInfo(buffer);
		case MESSAGE_TYPE_PARAMETER:
			return new MessageParameter(buffer);
		case MESSAGE_TYPE_FORMAT:
			return new MessageFormat(buffer, msgSize);
		case MESSAGE_TYPE_ADD_LOGGED_MSG:
			return new MessageAddLogged(buffer, msgSize);
		case MESSAGE_TYPE_DROPOUT:
			return new MessageDropout(buffer);
		case MESSAGE_TYPE_LOG:
			return new MessageLog(buffer, msgSize);
		case MESSAGE_TYPE_REMOVE_LOGGED_MSG:
		case MESSAGE_TYPE_SYNC:
			buffer.position(buffer.position() + msgSize);
			return null;
		default:
			//			buffer.position(buffer.position() + msgSize);
			//			System.err.println("Unknown message type: " + msgType);
		}
		return null;
	}

	private boolean checkMagicHeader() {
		boolean error = true;
		if ((buffer.get() & 0xFF) != 'U')
			error = false;
		if ((buffer.get() & 0xFF) != 'L')
			error = false;
		if ((buffer.get() & 0xFF) != 'o')
			error = false;
		if ((buffer.get() & 0xFF) != 'g')
			error = false;
		if ((buffer.get() & 0xFF) != 0x01)
			error = false;
		if ((buffer.get() & 0xFF) != 0x12)
			error = false;
		if ((buffer.get() & 0xFF) != 0x35)
			error = false;
		if ((buffer.get() & 0xFF) != 0x00 && !error) {
			System.out.println("ULog: Different version than expected. Will try anyway");
		}
		logStartTimestamp = buffer.getLong();
		return error;
	}

	private void applyMsg(Map<String, Object> update, MessageData msg) {
		applyMsgAsName(update, msg, msg.format.name + "_" + msg.multiID);
	}

	private void applyMsgAsName(Map<String, Object> update, MessageData msg, String msg_name) {
		final ArrayList<FieldFormat> fields = msg.format.fields;
		for (int i = 0; i < fields.size(); i++) {
			FieldFormat field = fields.get(i);
			if (field.isArray()) {
				for (int j = 0; j < field.size; j++) {
					update.put(msg_name + "." + field.name + "[" + j + "]", ((Object[]) msg.get(i))[j]);
				}
			} else {
				update.put(msg_name + "." + field.name, msg.get(i));
			//	System.out.println(msg_name+":"+msg.get(i));
			}
		}
	}

	// private classes

	private class ParamUpdate {
		private String name;
		private Object value;
		private long timestamp = -1;
		private ParamUpdate(String nm, Object v, long ts) {
			name = nm;
			value = v;
			timestamp = ts;
		}

		public String getName() {
			return name;
		}

		public Object getValue() {
			return value;
		}

		public long getTimestamp() {
			return timestamp;
		}
	}

	private class Subscription {
		public Subscription(MessageFormat f, int multiID) {
			this.format = f;
			this.multiID = multiID;
		}
		public MessageFormat format;
		public int multiID;
	}
}
