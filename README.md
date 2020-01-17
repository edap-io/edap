## edap

edap(easy distributed application platform) 易用的分布式应用平台。

目的打造成为一个跨服务微服务容器架构，以易用，极致的性能作为开发目标。

容器架构图如下：

![Segmentfault](./images/edap-container.svg)

容器可以同时支持RESTful接口以RPC(socket)的两种方式调用，服务描述使用proto文件进行定义。

大体的使用场景如下：

![Segmentfault](./images/edap.svg)