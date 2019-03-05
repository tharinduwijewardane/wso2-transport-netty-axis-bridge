package org.wso2.transports.http.bridge.util;

import com.google.gson.JsonParser;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.AddressingConstants;
import org.apache.axis2.addressing.AddressingHelper;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.engine.Handler;
import org.apache.axis2.engine.Phase;
import org.apache.axis2.transport.RequestResponseTransport;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transports.http.bridge.BridgeConstants;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;


/**
 * Class MessageUtils contains helper methods that are used to build the payload.
 */
public class MessageUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageUtils.class);
    private static final DeferredMessageBuilder messageBuilder = new DeferredMessageBuilder();

    private static boolean forceXmlValidation = false;
    private static boolean forceJSONValidation = false;
    private static boolean noAddressingHandler = false;

    private static volatile Handler addressingInHandler = null;

    public static void buildMessage(MessageContext msgCtx) {

        boolean byteChannelAlreadySet = false;

        HttpCarbonMessage httpCarbonMessage =
                (HttpCarbonMessage) msgCtx.getProperty(BridgeConstants.HTTP_CARBON_MESSAGE);

        HttpMessageDataStreamer httpMessageDataStreamer = new HttpMessageDataStreamer(httpCarbonMessage);
        String contentType = httpCarbonMessage.getHeader(HttpHeaderNames.CONTENT_TYPE.toString());

        long contentLength = BridgeConstants.NO_CONTENT_LENGTH_FOUND;
        String lengthStr = httpCarbonMessage.getHeader(HttpHeaderNames.CONTENT_LENGTH.toString());
        try {
            contentLength = lengthStr != null ? Long.parseLong(lengthStr) : contentLength;
            if (contentLength == BridgeConstants.NO_CONTENT_LENGTH_FOUND) {
                // read one byte to make sure the incoming stream has data
                contentLength = httpCarbonMessage.countMessageLengthTill(BridgeConstants.ONE_BYTE);
            }
        } catch (NumberFormatException e) {
        }

        InputStream in = httpMessageDataStreamer.getInputStream();
        ByteArrayOutputStream byteArrayOutputStream = null;

        BufferedInputStream bufferedInputStream = (BufferedInputStream) msgCtx
                .getProperty(BridgeConstants.BUFFERED_INPUT_STREAM);
        if (bufferedInputStream != null) {
            try {
                bufferedInputStream.reset();
                bufferedInputStream.mark(0);
            } catch (IOException e) {
//                handleException("Error while checking bufferedInputStream", e);
            }

        } else {
            bufferedInputStream = new BufferedInputStream(in);
            // TODO: need to handle properly for the moment lets use around 100k
            // buffer.
            bufferedInputStream.mark(128 * 1024);
            msgCtx.setProperty(BridgeConstants.BUFFERED_INPUT_STREAM,
                    bufferedInputStream);
        }

        boolean earlyBuild = false; // TODO: implement properly

        OMElement element = null;
        try {
            element = messageBuilder.getDocument(msgCtx, bufferedInputStream != null ? bufferedInputStream : in);
            if (element != null) {
                msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(element));
                msgCtx.setProperty(DeferredMessageBuilder.RELAY_FORMATTERS_MAP,
                        messageBuilder.getFormatters());
                msgCtx.setProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);

                earlyBuild = msgCtx.getProperty(BridgeConstants.RELAY_EARLY_BUILD) != null ? (Boolean) msgCtx
                        .getProperty(BridgeConstants.RELAY_EARLY_BUILD) : earlyBuild;

                if (!earlyBuild) {
                    processAddressing(msgCtx);
                }

                //force validation makes sure that the xml is well formed (not having multi root element), and the json
                // message is valid (not having any content after the final enclosing bracket)
                if (forceXmlValidation || forceJSONValidation) {
                    String rawData = null;
                    try {

                        if (BridgeConstants.JSON_CONTENT_TYPE.equals(contentType) && forceJSONValidation) {
                            rawData = byteArrayOutputStream.toString();
                            JsonParser jsonParser = new JsonParser();
                            jsonParser.parse(rawData);
                        }

                        msgCtx.getEnvelope().buildWithAttachments();
                        if (msgCtx.getEnvelope().getBody().getFirstElement() != null) {
                            msgCtx.getEnvelope().getBody().getFirstElement().buildNext();
                        }
                    } catch (Exception e) {
                        if (rawData == null) {
                            rawData = byteArrayOutputStream.toString();
                        }
                        LOGGER.error("Error while building the message.\n" + rawData);
                        msgCtx.setProperty(BridgeConstants.RAW_PAYLOAD, rawData);
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            //Clearing the buffer when there is an exception occurred.
//            consumeAndDiscardMessage(msgCtx);
            msgCtx.setProperty(BridgeConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
//            handleException("Error while building Passthrough stream", e);
        }
    }

    private static void processAddressing(MessageContext messageContext) throws AxisFault {
        if (noAddressingHandler) {
            return;
        } else if (addressingInHandler == null) {
            synchronized (messageBuilder) {
                if (addressingInHandler == null) {
                    AxisConfiguration axisConfig = messageContext.getConfigurationContext()
                            .getAxisConfiguration();
                    List<Phase> phases = axisConfig.getInFlowPhases();
                    boolean handlerFound = false;
                    for (Phase phase : phases) {
                        if ("Addressing".equals(phase.getName())) {
                            List<Handler> handlers = phase.getHandlers();
                            for (Handler handler : handlers) {
                                if ("AddressingInHandler".equals(handler.getName())) {
                                    addressingInHandler = handler;
                                    handlerFound = true;
                                    break;
                                }
                            }
                            break;
                        }
                    }

                    if (!handlerFound) {
                        noAddressingHandler = true;
                        return;
                    }
                }
            }
        }

        messageContext.setProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_IN_MESSAGES, "false");

        Object disableAddressingForOutGoing = null;
        if (messageContext.getProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES) != null) {
            disableAddressingForOutGoing = messageContext
                    .getProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES);
        }
        addressingInHandler.invoke(messageContext);

        if (disableAddressingForOutGoing != null) {
            messageContext.setProperty(AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES,
                    disableAddressingForOutGoing);
        }

        if (messageContext.getAxisOperation() == null) {
            return;
        }

        String mepString = messageContext.getAxisOperation().getMessageExchangePattern();

        if (isOneWay(mepString)) {
            Object requestResponseTransport = messageContext
                    .getProperty(RequestResponseTransport.TRANSPORT_CONTROL);
            if (requestResponseTransport != null) {

                Boolean disableAck = getDisableAck(messageContext);
                if (disableAck == null || disableAck.booleanValue() == false) {
                    ((RequestResponseTransport) requestResponseTransport)
                            .acknowledgeMessage(messageContext);
                }
            }
        } else if (AddressingHelper.isReplyRedirected(messageContext)
                && AddressingHelper.isFaultRedirected(messageContext)) {
            if (mepString.equals(WSDL2Constants.MEP_URI_IN_OUT)
                    || mepString.equals(WSDL2Constants.MEP_URI_IN_OUT)
                    || mepString.equals(WSDL2Constants.MEP_URI_IN_OUT)) {
                // OR, if 2 way operation but the response is intended to not
                // use the response channel of a 2-way transport
                // then we don't need to keep the transport waiting.

                Object requestResponseTransport = messageContext
                        .getProperty(RequestResponseTransport.TRANSPORT_CONTROL);
                if (requestResponseTransport != null) {

                    // We should send an early ack to the transport whenever
                    // possible, but some modules need
                    // to use the back channel, so we need to check if they have
                    // disabled this code.
                    Boolean disableAck = getDisableAck(messageContext);

                    if (disableAck == null || disableAck.booleanValue() == false) {
                        ((RequestResponseTransport) requestResponseTransport)
                                .acknowledgeMessage(messageContext);
                    }

                }
            }
        }
    }

    private static Boolean getDisableAck(MessageContext msgContext) throws AxisFault {
        // We should send an early ack to the transport whenever possible, but
        // some modules need
        // to use the back channel, so we need to check if they have disabled
        // this code.
        Boolean disableAck = (Boolean) msgContext
                .getProperty(Constants.Configuration.DISABLE_RESPONSE_ACK);
        if (disableAck == null) {
            disableAck = (Boolean) (msgContext.getAxisService() != null ? msgContext
                    .getAxisService().getParameterValue(
                            Constants.Configuration.DISABLE_RESPONSE_ACK) : null);
        }

        return disableAck;
    }


    private static boolean isOneWay(String mepString) {
        return (mepString.equals(WSDL2Constants.MEP_URI_IN_ONLY)
                || mepString.equals(WSDL2Constants.MEP_URI_IN_ONLY) || mepString
                .equals(WSDL2Constants.MEP_URI_IN_ONLY));
    }

    /**
     * Function to check given inputstream is empty or not
     * Used to check whether content of the payload input stream is empty or not
     *
     * @param inputStream target inputstream
     * @return true if it is a empty stream
     * @throws IOException
     */
    public static boolean isEmptyPayloadStream(InputStream inputStream) throws IOException {

        boolean isEmptyPayload = true;

        if (inputStream != null) {
            // read ahead few characters to see if the stream is valid.
            ReadOnlyBIS readOnlyStream = new ReadOnlyBIS(inputStream);

            /**
             * Checks for all empty or all whitespace streams and if found  sets isEmptyPayload to false. The while
             * loop exits if found any character other than space or end of stream reached.
             **/
            int c = readOnlyStream.read();
            while (c != -1) {
                if (c != 32) {
                    //if not a space, should be some character in entity body
                    isEmptyPayload = false;
                    break;
                }
                c = readOnlyStream.read();
            }
            readOnlyStream.reset();
            inputStream.reset();
        }

        return isEmptyPayload;
    }

    /**
     * An Un-closable, Read-Only, Reusable, BufferedInputStream.
     */
    private static class ReadOnlyBIS extends BufferedInputStream {
        private static final String LOG_STREAM = "org.apache.synapse.transport.passthru.util.ReadOnlyStream";
        private static final Log logger = LogFactory.getLog(LOG_STREAM);

        public ReadOnlyBIS(InputStream inputStream) {
            super(inputStream);
            super.mark(Integer.MAX_VALUE);
            if (logger.isDebugEnabled()) {
                logger.debug("<init>");
            }
        }

        @Override
        public void close() throws IOException {
            super.reset();
            //super.mark(Integer.MAX_VALUE);
            if (logger.isDebugEnabled()) {
                logger.debug("#close");
            }
        }

        @Override
        public void mark(int readlimit) {
            if (logger.isDebugEnabled()) {
                logger.debug("#mark");
            }
        }

        @Override
        public boolean markSupported() {
            return true; // but we don't mark.
        }

        @Override
        public long skip(long n) {
            if (logger.isDebugEnabled()) {
                logger.debug("#skip");
            }
            return 0;
        }
    }
}