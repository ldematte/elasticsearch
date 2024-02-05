module elasticsearch.modules.index.size.main {

    requires org.elasticsearch.base;
    requires org.elasticsearch.server;
    requires org.elasticsearch.xcontent;
    requires org.apache.logging.log4j;
    requires org.apache.lucene.core;

    provides org.elasticsearch.features.FeatureSpecification
        with
            org.elasticsearch.metering.indexsize.IndexSizeFeatures;

    exports org.elasticsearch.metering.indexsize;
}
