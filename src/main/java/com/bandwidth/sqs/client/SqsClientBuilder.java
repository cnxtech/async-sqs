package com.bandwidth.sqs.client;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.bandwidth.sqs.action.sender.BaseSqsRequestSender;
import com.bandwidth.sqs.action.sender.RetryingSqsRequestSender;
import com.bandwidth.sqs.action.sender.SqsRequestSender;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;

import io.reactivex.functions.Function;

public class SqsClientBuilder<T> {
    public static final int DEFAULT_RETRY_COUNT = 3;
    public static final AWSCredentialsProvider DEFAULT_CREDENTIALS_PROVIDER = new DefaultAWSCredentialsProviderChain();
    public static final AsyncHttpClient DEFAULT_ASYNC_HTTP_CLIENT = new DefaultAsyncHttpClient(
            new DefaultAsyncHttpClientConfig.Builder()
                    .setKeepAlive(true)
                    .setConnectionTtl(10000)//10 seconds
                    .setMaxConnections(Integer.MAX_VALUE)
                    .setMaxConnectionsPerHost(Integer.MAX_VALUE)
                    .build()
    );

    public final Function<String, T> deserialize;
    public final Function<T, String> serialize;

    private int retryCount = DEFAULT_RETRY_COUNT;
    private AWSCredentialsProvider credentialsProvider = DEFAULT_CREDENTIALS_PROVIDER;
    private AsyncHttpClient httpClient = DEFAULT_ASYNC_HTTP_CLIENT;

    public SqsClientBuilder(Function<String, T> deserialize, Function<T, String> serialize) {
        this.deserialize = deserialize;
        this.serialize = serialize;
    }

    public SqsClientBuilder<T> retryCount(int retryCount) {
        this.retryCount = retryCount;
        return this;
    }

    public SqsClientBuilder<T> credentialsProvider(AWSCredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        return this;
    }

    public SqsClientBuilder<T> httpClient(AsyncHttpClient asyncHttpClient) {
        this.httpClient = asyncHttpClient;
        return this;
    }


    /**
     * Converts the builder to a different type by defining mapping functions between the original and new type
     *
     * @param map        Map from the original value, to the new value
     * @param inverseMap Map from the new value, back to the original value
     */
    public <U> SqsClientBuilder<U> map(Function<T, U> map, Function<U, T> inverseMap) {
        Function<String, U> stringMap = string -> map.apply(deserialize.apply(string));
        Function<U, String> stringInverseMap = data -> serialize.apply(inverseMap.apply(data));
        return new SqsClientBuilder<>(stringMap, stringInverseMap)
                .retryCount(retryCount)
                .credentialsProvider(credentialsProvider)
                .httpClient(httpClient);
    }


    public SqsClient<T> build() {
        SqsRequestSender requestSender = new RetryingSqsRequestSender(retryCount,
                new BaseSqsRequestSender(httpClient, credentialsProvider));
        return new SqsClient<>(requestSender, deserialize, serialize);
    }
}
