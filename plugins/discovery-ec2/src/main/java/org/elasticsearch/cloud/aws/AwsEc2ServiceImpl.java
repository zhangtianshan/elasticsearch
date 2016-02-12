/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cloud.aws;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.auth.SystemPropertiesCredentialsProvider;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.retry.RetryPolicy;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cloud.aws.network.Ec2NameResolver;
import org.elasticsearch.cloud.aws.node.Ec2CustomNodeAttributes;
import org.elasticsearch.cluster.node.DiscoveryNodeService;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;

import java.util.Random;

/**
 *
 */
public class AwsEc2ServiceImpl extends AbstractLifecycleComponent<AwsEc2Service> implements AwsEc2Service {

    public static final String EC2_METADATA_URL = "http://169.254.169.254/latest/meta-data/";

    private AmazonEC2Client client;

    @Inject
    public AwsEc2ServiceImpl(Settings settings, NetworkService networkService, DiscoveryNodeService discoveryNodeService) {
        super(settings);
        // add specific ec2 name resolver
        networkService.addCustomNameResolver(new Ec2NameResolver(settings));
        discoveryNodeService.addCustomAttributeProvider(new Ec2CustomNodeAttributes(settings));
    }

    @Override
    public synchronized AmazonEC2 client() {
        if (client != null) {
            return client;
        }

        ClientConfiguration clientConfiguration = new ClientConfiguration();
        // the response metadata cache is only there for diagnostics purposes,
        // but can force objects from every response to the old generation.
        clientConfiguration.setResponseMetadataCacheSize(0);
        clientConfiguration.setProtocol(CLOUD_EC2.PROTOCOL_SETTING.get(settings));
        String key = CLOUD_EC2.KEY_SETTING.get(settings);
        String secret = CLOUD_EC2.SECRET_SETTING.get(settings);

        String proxyHost = CLOUD_EC2.PROXY_HOST_SETTING.get(settings);
        if (proxyHost != null) {
            Integer proxyPort = CLOUD_EC2.PROXY_PORT_SETTING.get(settings);
            String proxyUsername = CLOUD_EC2.PROXY_USERNAME_SETTING.get(settings);
            String proxyPassword = CLOUD_EC2.PROXY_PASSWORD_SETTING.get(settings);

            clientConfiguration
                .withProxyHost(proxyHost)
                .withProxyPort(proxyPort)
                .withProxyUsername(proxyUsername)
                .withProxyPassword(proxyPassword);
        }

        // #155: we might have 3rd party users using older EC2 API version
        String awsSigner = CLOUD_EC2.SIGNER_SETTING.get(settings);
        if (Strings.hasText(awsSigner)) {
            logger.debug("using AWS API signer [{}]", awsSigner);
            AwsSigner.configureSigner(awsSigner, clientConfiguration);
        }

        // Increase the number of retries in case of 5xx API responses
        final Random rand = Randomness.get();
        RetryPolicy retryPolicy = new RetryPolicy(
                RetryPolicy.RetryCondition.NO_RETRY_CONDITION,
                new RetryPolicy.BackoffStrategy() {
                    @Override
                    public long delayBeforeNextRetry(AmazonWebServiceRequest originalRequest,
                                                     AmazonClientException exception,
                                                     int retriesAttempted) {
                        // with 10 retries the max delay time is 320s/320000ms (10 * 2^5 * 1 * 1000)
                        logger.warn("EC2 API request failed, retry again. Reason was:", exception);
                        return 1000L * (long) (10d * Math.pow(2, retriesAttempted / 2.0d) * (1.0d + rand.nextDouble()));
                    }
                },
                10,
                false);
        clientConfiguration.setRetryPolicy(retryPolicy);

        AWSCredentialsProvider credentials;

        if (key == null && secret == null) {
            credentials = new AWSCredentialsProviderChain(
                    new EnvironmentVariableCredentialsProvider(),
                    new SystemPropertiesCredentialsProvider(),
                    new InstanceProfileCredentialsProvider()
            );
        } else {
            credentials = new AWSCredentialsProviderChain(
                    new StaticCredentialsProvider(new BasicAWSCredentials(key, secret))
            );
        }

        this.client = new AmazonEC2Client(credentials, clientConfiguration);

        String endpoint = CLOUD_EC2.ENDPOINT_SETTING.get(settings);
        if (endpoint != null) {
            logger.debug("using explicit ec2 endpoint [{}]", endpoint);
            client.setEndpoint(endpoint);
        } else if (CLOUD_EC2.REGION_SETTING.exists(settings)) {
            String region = CLOUD_EC2.REGION_SETTING.get(settings);
            if (region.equals("us-east-1") || region.equals("us-east")) {
                endpoint = "ec2.us-east-1.amazonaws.com";
            } else if (region.equals("us-west") || region.equals("us-west-1")) {
                endpoint = "ec2.us-west-1.amazonaws.com";
            } else if (region.equals("us-west-2")) {
                endpoint = "ec2.us-west-2.amazonaws.com";
            } else if (region.equals("ap-southeast") || region.equals("ap-southeast-1")) {
                endpoint = "ec2.ap-southeast-1.amazonaws.com";
            } else if (region.equals("us-gov-west") || region.equals("us-gov-west-1")) {
                endpoint = "ec2.us-gov-west-1.amazonaws.com";
            } else if (region.equals("ap-southeast-2")) {
                endpoint = "ec2.ap-southeast-2.amazonaws.com";
            } else if (region.equals("ap-northeast") || region.equals("ap-northeast-1")) {
                endpoint = "ec2.ap-northeast-1.amazonaws.com";
            } else if (region.equals("ap-northeast-2")) {
                endpoint = "ec2.ap-northeast-2.amazonaws.com";
            } else if (region.equals("eu-west") || region.equals("eu-west-1")) {
                endpoint = "ec2.eu-west-1.amazonaws.com";
            } else if (region.equals("eu-central") || region.equals("eu-central-1")) {
                endpoint = "ec2.eu-central-1.amazonaws.com";
            } else if (region.equals("sa-east") || region.equals("sa-east-1")) {
                endpoint = "ec2.sa-east-1.amazonaws.com";
            } else if (region.equals("cn-north") || region.equals("cn-north-1")) {
                endpoint = "ec2.cn-north-1.amazonaws.com.cn";
            } else {
                throw new IllegalArgumentException("No automatic endpoint could be derived from region [" + region + "]");
            }
            logger.debug("using ec2 region [{}], with endpoint [{}]", region, endpoint);
            client.setEndpoint(endpoint);
        }

        return this.client;
    }

    @Override
    protected void doStart() throws ElasticsearchException {
    }

    @Override
    protected void doStop() throws ElasticsearchException {
    }

    @Override
    protected void doClose() throws ElasticsearchException {
        if (client != null) {
            client.shutdown();
        }
    }
}
