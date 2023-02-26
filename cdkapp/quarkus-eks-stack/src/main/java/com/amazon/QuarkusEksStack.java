/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package com.amazon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.cdk.lambdalayer.kubectl.v24.KubectlV24Layer;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.eks.AlbControllerOptions;
import software.amazon.awscdk.services.eks.AlbControllerVersion;
import software.amazon.awscdk.services.eks.CapacityType;
import software.amazon.awscdk.services.eks.Cluster;
import software.amazon.awscdk.services.eks.HelmChartOptions;
import software.amazon.awscdk.services.eks.KubernetesManifest;
import software.amazon.awscdk.services.eks.KubernetesVersion;
import software.amazon.awscdk.services.eks.NodegroupAmiType;
import software.amazon.awscdk.services.eks.NodegroupOptions;
import software.amazon.awscdk.services.eks.ServiceAccount;
import software.amazon.awscdk.services.eks.ServiceAccountOptions;
import software.amazon.awscdk.services.events.EventBus;
import software.amazon.awscdk.services.iam.AccountRootPrincipal;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

public class QuarkusEksStack extends Stack {

  static Map<String, ? extends Object> namespaceManifestConfig = Map.of(
      "apiVersion", "v1",
      "kind", "Namespace",
      "metadata", Map.of(
          "name", "quarkus"
                        )
                                                                       );

  static String metricsServerConfig = "{\n" +
      "\"resources\": {\n" +
      "    \"requests\": {\n" +
      "        \"cpu\": \"0.25\",\n" +
      "        \"memory\": \"0.5Gi\"\n" +
      "    }\n" +
      "}\n" +
      "}";

  public QuarkusEksStack(final Construct scope, final String id) {
    this(scope, id, null);
  }

  public QuarkusEksStack(final Construct scope, final String id, final StackProps props) {
    super(scope, id, props);

    // First we need to create a VPC
    Vpc vpc = Vpc.Builder
        .create(this, "QuarkusEKSVpc")
        .vpcName("QuarkusEKSVPC")
        .enableDnsHostnames(true)
        .enableDnsSupport(true)
        .maxAzs(2)
        .reservedAzs(2)
        .subnetConfiguration(List.of(SubnetConfiguration
                                         .builder()
                                         .name("ingress")
                                         .subnetType(SubnetType.PUBLIC)
                                         .build(), SubnetConfiguration
                                         .builder()
                                         .name("application")
                                         .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                         .build()))
        .build();


    // Entire EKS Cluster managed by this Role
    IRole eksMasterRole = Role.Builder
        .create(this, "EksMasterRole")
        .roleName(this.getStackName() + "_EKSMasterRole")
        .assumedBy(new AccountRootPrincipal())
        .build();

    // Second step is to create the EKS cluster
    Cluster eksCluster = Cluster.Builder
        .create(this, "Cluster")
        .clusterName("QuarkusEKSCluster")
        .version(KubernetesVersion.V1_24)
        .vpc(vpc)
        .vpcSubnets(List.of(SubnetSelection
                                .builder()
                                .subnetType(SubnetType.PUBLIC)
                                .build(), SubnetSelection
                                .builder()
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .build()))
        .outputClusterName(true)
        .outputConfigCommand(true)
        .outputMastersRoleArn(true)
        .mastersRole(eksMasterRole)
        .kubectlLayer(new KubectlV24Layer(this, "kubectl"))
        .albController(AlbControllerOptions
                           .builder()
                           .version(AlbControllerVersion.V2_4_1)
                           .build())
        .defaultCapacity(0)
        .build();

    InstanceType       t3Medium    = new InstanceType("t3.medium");
    List<InstanceType> x86_x64List = new ArrayList<>();
    x86_x64List.add(t3Medium);

    InstanceType       t4gMedium = new InstanceType("t4g.medium");
    List<InstanceType> arm64List = new ArrayList<>();
    arm64List.add(t4gMedium);

    NodegroupOptions x86 = NodegroupOptions
        .builder()
        .amiType(NodegroupAmiType.AL2_X86_64)
        .nodegroupName("X86_64ManagedNodeGroup")
        .instanceTypes(x86_x64List)
        .subnets(SubnetSelection
                     .builder()
                     .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                     .build())
        .minSize(1)
        .maxSize(4)
        .desiredSize(1)
        .capacityType(CapacityType.SPOT)
        .build();

    NodegroupOptions arm64 = NodegroupOptions
        .builder()
        .amiType(NodegroupAmiType.AL2_ARM_64)
        .nodegroupName("ARM64ManagedNodeGroup")
        .instanceTypes(arm64List)
        .subnets(SubnetSelection
                     .builder()
                     .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                     .build())
        .minSize(2)
        .maxSize(3)
        .desiredSize(2)
        .capacityType(CapacityType.ON_DEMAND)
        .build();

    eksCluster.addNodegroupCapacity("x86_64", x86);
    eksCluster.addNodegroupCapacity("Arm64", arm64);

    // And a service account for pod permissions

    KubernetesManifest manifest = KubernetesManifest.Builder
        .create(this, "read-only")
        .cluster(eksCluster)
        .manifest(
            List.of(namespaceManifestConfig))
        .build();

    Map<String, Object> metricsServerMap = null;
    try {
      metricsServerMap = new ObjectMapper().readValue(metricsServerConfig, HashMap.class);
    }
    catch (JsonProcessingException e) {
      e.printStackTrace();
    }

    // Now let's install metrics-server for HPA

    HelmChartOptions helmChartOptionsMetricsServer = HelmChartOptions
        .builder()
        .chart("metrics-server")
        .version("3.8.3")
        .release("metricsserver")
        .repository(
            "https://kubernetes-sigs.github.io/metrics-server/")
        .namespace("kube-system")
        .values(metricsServerMap)
        .wait(true)
        .build();

    eksCluster.addHelmChart("metrics-server",
                            helmChartOptionsMetricsServer);

    ServiceAccountOptions serviceAccountOptions = ServiceAccountOptions
        .builder()
        .namespace("quarkus")
        .name("serviceaccount.quarkus.amazon.com")
        .build();

    ServiceAccount serviceAccount = eksCluster.addServiceAccount("sa",
                                                                 serviceAccountOptions);
    serviceAccount
        .getNode()
        .addDependency(manifest);

    // Now we need to create a DynamoDB table
    Table table = Table.Builder
        .create(this, "QuarkusEksTable")
        .partitionKey(Attribute
                          .builder()
                          .name("Id")
                          .type(AttributeType.STRING)
                          .build())
        .tableName("Customer")
        .readCapacity(5)
        .writeCapacity(5)
        .removalPolicy(RemovalPolicy.DESTROY)
        .build();

    table.grantReadWriteData(serviceAccount);

    // And we need to create an EventBridge EventBus
    EventBus eventBus = EventBus.Builder
        .create(this, "QuarkusEksBus")
        .eventBusName("com.amazon.customerservice")
        .build();

    eventBus.grantPutEventsTo(serviceAccount);

    Repository ecrRepo = Repository.Builder
        .create(this, "QuarkusEcrRepo")
        .repositoryName("aws-eks-quarkus-example")
        .imageScanOnPush(true)
        .removalPolicy(RemovalPolicy.RETAIN)
        .build();

    CfnOutput.Builder
        .create(this, "Cluster name")
        .value(eksCluster.getClusterName())
        .build();
    CfnOutput.Builder
        .create(this, "ECR Repository")
        .value(ecrRepo.getRepositoryName());
  }
}
