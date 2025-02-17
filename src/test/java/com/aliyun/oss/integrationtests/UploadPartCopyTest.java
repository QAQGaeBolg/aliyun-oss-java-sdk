/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.oss.integrationtests;

import static com.aliyun.oss.integrationtests.TestConfig.OSS_TEST_ENDPOINT;
import static com.aliyun.oss.integrationtests.TestConstants.ENTITY_TOO_SMALL_ERR;
import static com.aliyun.oss.integrationtests.TestUtils.calcMultipartsETag;
import static com.aliyun.oss.integrationtests.TestUtils.claimUploadId;
import static com.aliyun.oss.integrationtests.TestUtils.composeLocation;
import static com.aliyun.oss.integrationtests.TestUtils.genFixedLengthInputStream;
import static com.aliyun.oss.integrationtests.TestUtils.waitForCacheExpiration;
import static com.aliyun.oss.integrationtests.TestConstants.NO_SUCH_BUCKET_ERR;
import static com.aliyun.oss.integrationtests.TestConstants.NO_SUCH_KEY_ERR;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.*;

import org.junit.Test;

import com.aliyun.oss.OSSErrorCode;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadResult;
import com.aliyun.oss.model.ListPartsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.PartListing;
import com.aliyun.oss.model.PutObjectResult;
import com.aliyun.oss.model.UploadPartCopyRequest;
import com.aliyun.oss.model.UploadPartCopyResult;

public class UploadPartCopyTest extends TestBase {
    
    private static final int LIST_PART_MAX_RETURNS = 1000;
    
    @Test
    public void testNormalUploadPartCopy() {
        final String sourceBucket = super.bucketName + "-" + "normal-upload-part-copy-source";
        final String targetBucket = super.bucketName + "-" + "normal-upload-part-copy-target";
        final String sourceKey = "normal-upload-part-copy-object-source";
        final String targetKey = "normal-upload-part-copy-object-target";
        final long partSize = 128 * 1024;     //128KB
        
        try {
            ossClient.createBucket(sourceBucket);
            ossClient.createBucket(targetBucket);
            
            waitForCacheExpiration(5);
            
            // Put object into source bucket
            String eTag = null;
            try {
                InputStream instream = genFixedLengthInputStream(partSize);
                PutObjectResult result = ossClient.putObject(sourceBucket, sourceKey, instream, null);
                eTag = result.getETag();
            } catch (Exception e) {
                Assertions.fail(e.getMessage());
            }
            
            // Claim upload id for target bucket 
            String uploadId = claimUploadId(ossClient, targetBucket, targetKey);
            
            // Upload part copy
            final int partNumber = 1;
            List<PartETag> partETags = new ArrayList<PartETag>();
            UploadPartCopyRequest uploadPartCopyRequest = 
                    new UploadPartCopyRequest(sourceBucket, sourceKey, targetBucket, targetKey);
            uploadPartCopyRequest.setPartNumber(partNumber);
            uploadPartCopyRequest.setUploadId(uploadId);
            UploadPartCopyResult uploadPartCopyResult = ossClient.uploadPartCopy(uploadPartCopyRequest);
            partETags.add(uploadPartCopyResult.getPartETag());
            Assertions.assertEquals(eTag, uploadPartCopyResult.getETag());
            Assertions.assertEquals(partNumber, uploadPartCopyResult.getPartNumber());
            Assertions.assertEquals(uploadPartCopyResult.getRequestId().length(), REQUEST_ID_LEN);
            
            ListPartsRequest listPartsRequest = new ListPartsRequest(targetBucket, targetKey, uploadId);
            PartListing partListing = ossClient.listParts(listPartsRequest);
            Assertions.assertEquals(1, partListing.getParts().size());
            Assertions.assertEquals(targetBucket, partListing.getBucketName());
            Assertions.assertEquals(targetKey, partListing.getKey());
            Assertions.assertEquals(uploadId, partListing.getUploadId());
            Assertions.assertEquals(LIST_PART_MAX_RETURNS, partListing.getMaxParts().intValue());
            Assertions.assertNotNull(partListing.getNextPartNumberMarker());
            Assertions.assertFalse(partListing.isTruncated());
            Assertions.assertEquals(partListing.getRequestId().length(), REQUEST_ID_LEN);
            
            // Complete multipart upload
            CompleteMultipartUploadRequest completeMultipartUploadRequest = 
                    new CompleteMultipartUploadRequest(targetBucket, targetKey, uploadId, partETags);
            CompleteMultipartUploadResult completeMultipartUploadResult =
                    ossClient.completeMultipartUpload(completeMultipartUploadRequest);
            Assertions.assertEquals(composeLocation(ossClient, OSS_TEST_ENDPOINT, targetBucket, targetKey), 
                    completeMultipartUploadResult.getLocation());
            Assertions.assertEquals(targetBucket, completeMultipartUploadResult.getBucketName());
            Assertions.assertEquals(targetKey, completeMultipartUploadResult.getKey());
            Assertions.assertEquals(calcMultipartsETag(partETags), completeMultipartUploadResult.getETag());
            Assertions.assertEquals(completeMultipartUploadResult.getRequestId().length(), REQUEST_ID_LEN);
            
            // Get uploaded object
            OSSObject o = ossClient.getObject(targetBucket, targetKey);
            final long objectSize = 1 * partSize;
            Assertions.assertEquals(objectSize, o.getObjectMetadata().getContentLength());
            Assertions.assertEquals(calcMultipartsETag(partETags), o.getObjectMetadata().getETag());
            Assertions.assertEquals(o.getRequestId().length(), REQUEST_ID_LEN);
        } catch (Exception e) {
            Assertions.fail(e.getMessage());
        } finally {
            deleteBucketWithObjects(ossClient, sourceBucket);
            deleteBucketWithObjects(ossClient, targetBucket);
        }
    }
    
    @Test
    public void testUnormalUploadPartCopy() {
        final String sourceBucket = super.bucketName + "-" + "unormal-upload-part-copy-source";
        final String targetBucket = super.bucketName + "-" + "unormal-upload-part-copy-target";
        final String sourceKey = "unormal-upload-part-copy-object-source";
        final String targetKey = "unormal-upload-part-copy-object-target";
        
        // Set length of parts less than minimum limit(100KB)
        final long partSize = 64 * 1024;     //64KB
        
        try {
            ossClient.createBucket(sourceBucket);
            ossClient.createBucket(targetBucket);
            
            waitForCacheExpiration(5);
            
            // Put object into source bucket
            String eTag = null;
            try {
                InputStream instream = genFixedLengthInputStream(partSize);
                PutObjectResult result = ossClient.putObject(sourceBucket, sourceKey, instream, null);
                eTag = result.getETag();
            } catch (Exception e) {
                Assertions.fail(e.getMessage());
            }
            
            // Claim upload id for target bucket 
            String uploadId = claimUploadId(ossClient, targetBucket, targetKey);
            
            // Copy part under non-existent source bucket
            final String nonexistentSourceBucket = "nonexistent-source-bucket";
            final int partNumber = 1;
            try {
                UploadPartCopyRequest uploadPartCopyRequest = 
                        new UploadPartCopyRequest(nonexistentSourceBucket, sourceKey, targetBucket, targetKey);
                uploadPartCopyRequest.setPartNumber(partNumber);
                uploadPartCopyRequest.setUploadId(uploadId);
                ossClient.uploadPartCopy(uploadPartCopyRequest);
                Assertions.fail("Upload part copy should not be successfuly");
            } catch (OSSException e) {
                Assertions.assertEquals(OSSErrorCode.NO_SUCH_BUCKET, e.getErrorCode());
                Assertions.assertTrue(e.getMessage().startsWith(NO_SUCH_BUCKET_ERR));
            }
            
            // Copy part from non-existent source key
            final String nonexistentSourceKey = "nonexistent-source-key";
            try {
                UploadPartCopyRequest uploadPartCopyRequest = 
                        new UploadPartCopyRequest(sourceBucket, nonexistentSourceKey, targetBucket, targetKey);
                uploadPartCopyRequest.setPartNumber(partNumber);
                uploadPartCopyRequest.setUploadId(uploadId);
                ossClient.uploadPartCopy(uploadPartCopyRequest);
                Assertions.fail("Upload part copy should not be successfuly");
            } catch (OSSException e) {
                Assertions.assertEquals(OSSErrorCode.NO_SUCH_KEY, e.getErrorCode());
                Assertions.assertTrue(e.getMessage().startsWith(NO_SUCH_KEY_ERR));
            }
            
            // Copy part to non-existent target bucket
            final String nonexistentTargetBucket = "nonexistent-target-key";
            try {
                UploadPartCopyRequest uploadPartCopyRequest = 
                        new UploadPartCopyRequest(sourceBucket, sourceKey, nonexistentTargetBucket, targetKey);
                uploadPartCopyRequest.setPartNumber(partNumber);
                uploadPartCopyRequest.setUploadId(uploadId);
                ossClient.uploadPartCopy(uploadPartCopyRequest);
                Assertions.fail("Upload part copy should not be successfuly");
            } catch (OSSException e) {
                Assertions.assertEquals(OSSErrorCode.NO_SUCH_BUCKET, e.getErrorCode());
                Assertions.assertTrue(e.getMessage().startsWith(NO_SUCH_BUCKET_ERR));
            }
            
            // Upload part copy
            List<PartETag> partETags = new ArrayList<PartETag>();
            try {
                UploadPartCopyRequest uploadPartCopyRequest = 
                        new UploadPartCopyRequest(sourceBucket, sourceKey, targetBucket, targetKey);
                uploadPartCopyRequest.setPartNumber(partNumber);
                uploadPartCopyRequest.setUploadId(uploadId);
                UploadPartCopyResult uploadPartCopyResult = ossClient.uploadPartCopy(uploadPartCopyRequest);
                partETags.add(uploadPartCopyResult.getPartETag());
                Assertions.assertEquals(eTag, uploadPartCopyResult.getETag());
                Assertions.assertEquals(partNumber, uploadPartCopyResult.getPartNumber());
                
                uploadPartCopyRequest = 
                        new UploadPartCopyRequest(sourceBucket, sourceKey, targetBucket, targetKey);
                uploadPartCopyRequest.setPartNumber(partNumber + 1);
                uploadPartCopyRequest.setUploadId(uploadId);
                uploadPartCopyResult = ossClient.uploadPartCopy(uploadPartCopyRequest);
                partETags.add(uploadPartCopyResult.getPartETag());
                Assertions.assertEquals(eTag, uploadPartCopyResult.getETag());
                Assertions.assertEquals(partNumber + 1, uploadPartCopyResult.getPartNumber());
                
                ListPartsRequest listPartsRequest = new ListPartsRequest(targetBucket, targetKey, uploadId);
                PartListing partListing = ossClient.listParts(listPartsRequest);
                Assertions.assertEquals(2, partListing.getParts().size());
                Assertions.assertEquals(targetBucket, partListing.getBucketName());
                Assertions.assertEquals(targetKey, partListing.getKey());
                Assertions.assertEquals(uploadId, partListing.getUploadId());
                Assertions.assertEquals(LIST_PART_MAX_RETURNS, partListing.getMaxParts().intValue());
                Assertions.assertNotNull(partListing.getNextPartNumberMarker());
                Assertions.assertFalse(partListing.isTruncated());
            } catch (Exception e) {
                Assertions.fail(e.getMessage());
            }
            
            // Try to complete multipart upload with all uploaded parts
            try {
                CompleteMultipartUploadRequest completeMultipartUploadRequest = 
                        new CompleteMultipartUploadRequest(targetBucket, targetKey, uploadId, partETags);
                ossClient.completeMultipartUpload(completeMultipartUploadRequest);
                Assertions.fail("Upload part copy should not be successfuly");
            } catch (OSSException e) {
                Assertions.assertEquals(OSSErrorCode.ENTITY_TOO_SMALL, e.getErrorCode());
                Assertions.assertTrue(e.getMessage().startsWith(ENTITY_TOO_SMALL_ERR));
            }
            
            // Abort the incompleted multipart upload
            try {
                AbortMultipartUploadRequest abortMultipartUploadRequest = 
                        new AbortMultipartUploadRequest(targetBucket, targetKey, uploadId);
                ossClient.abortMultipartUpload(abortMultipartUploadRequest);
            } catch (Exception e) {
                Assertions.fail(e.getMessage());
            }
        } catch (Exception e) {
            Assertions.fail(e.getMessage());
        } finally {
            deleteBucketWithObjects(ossClient, sourceBucket);
            deleteBucketWithObjects(ossClient, targetBucket);
        }
    }
    
    @Test
    public void testNormalUploadPartCopyByRange() {
        final String sourceBucket = super.bucketName + "-" + "normal-upload-part-copy-range-source";
        final String targetBucket = super.bucketName + "-" + "normal-upload-part-copy-range-target";
        final String sourceKey = "normal-upload-part-copy-by-range-object-source";
        final String targetKey = "normal-upload-part-copy-by-range-object-target";
        final long partSize = 128 * 1024;     //128KB
        
        try {
            ossClient.createBucket(sourceBucket);
            ossClient.createBucket(targetBucket);
            
            waitForCacheExpiration(5);
            
            // Put object into source bucket
            final long inputStreamLength = partSize * 4;
            try {
                InputStream instream = genFixedLengthInputStream(inputStreamLength);
                ossClient.putObject(sourceBucket, sourceKey, instream, null);
            } catch (Exception e) {
                Assertions.fail(e.getMessage());
            }
            
            // Claim upload id for target bucket 
            String uploadId = claimUploadId(ossClient, targetBucket, targetKey);
            
            // Upload part copy
            final int partNumber = 1;
            final long beginIndex = partSize;
            List<PartETag> partETags = new ArrayList<PartETag>();
            UploadPartCopyRequest uploadPartCopyRequest = 
                    new UploadPartCopyRequest(sourceBucket, sourceKey, targetBucket, targetKey);
            uploadPartCopyRequest.setPartNumber(partNumber);
            uploadPartCopyRequest.setUploadId(uploadId);
            uploadPartCopyRequest.setBeginIndex(beginIndex);
            uploadPartCopyRequest.setPartSize(partSize);
            UploadPartCopyResult uploadPartCopyResult = ossClient.uploadPartCopy(uploadPartCopyRequest);
            partETags.add(uploadPartCopyResult.getPartETag());
            Assertions.assertEquals(partNumber, uploadPartCopyResult.getPartNumber());
            Assertions.assertEquals(uploadPartCopyResult.getRequestId().length(), REQUEST_ID_LEN);
            
            ListPartsRequest listPartsRequest = new ListPartsRequest(targetBucket, targetKey, uploadId);
            PartListing partListing = ossClient.listParts(listPartsRequest);
            Assertions.assertEquals(1, partListing.getParts().size());
            Assertions.assertEquals(targetBucket, partListing.getBucketName());
            Assertions.assertEquals(targetKey, partListing.getKey());
            Assertions.assertEquals(uploadId, partListing.getUploadId());
            Assertions.assertEquals(LIST_PART_MAX_RETURNS, partListing.getMaxParts().intValue());
            Assertions.assertNotNull(partListing.getNextPartNumberMarker());
            Assertions.assertFalse(partListing.isTruncated());
            Assertions.assertEquals(partListing.getRequestId().length(), REQUEST_ID_LEN);
            
            // Complete multipart upload
            CompleteMultipartUploadRequest completeMultipartUploadRequest = 
                    new CompleteMultipartUploadRequest(targetBucket, targetKey, uploadId, partETags);
            CompleteMultipartUploadResult completeMultipartUploadResult =
                    ossClient.completeMultipartUpload(completeMultipartUploadRequest);
            Assertions.assertEquals(composeLocation(ossClient, OSS_TEST_ENDPOINT, targetBucket, targetKey), 
                    completeMultipartUploadResult.getLocation());
            Assertions.assertEquals(targetBucket, completeMultipartUploadResult.getBucketName());
            Assertions.assertEquals(targetKey, completeMultipartUploadResult.getKey());
            Assertions.assertEquals(calcMultipartsETag(partETags), completeMultipartUploadResult.getETag());
            Assertions.assertEquals(completeMultipartUploadResult.getRequestId().length(), REQUEST_ID_LEN);
            
            // Get uploaded object
            OSSObject o = ossClient.getObject(targetBucket, targetKey);
            final long objectSize = 1 * partSize;
            Assertions.assertEquals(objectSize, o.getObjectMetadata().getContentLength());
            Assertions.assertEquals(calcMultipartsETag(partETags), o.getObjectMetadata().getETag());
            Assertions.assertEquals(o.getRequestId().length(), REQUEST_ID_LEN);
        } catch (Exception e) {
            Assertions.fail(e.getMessage());
        } finally {
            deleteBucketWithObjects(ossClient, sourceBucket);
            deleteBucketWithObjects(ossClient, targetBucket);
        }
    }
    
    @Test
    public void testNormalUploadPartCopyWithSpecialChars() {
        final String sourceBucket = super.bucketName + "-" + "normal-upload-part-copy-spec-source";
        final String targetBucket = super.bucketName + "-" + "normal-upload-part-copy-spec-target";
        final String sourceKey = "测\\r试-中.~,+\"'*&￥#@%！（文）+字符|？/.zip";
        final String targetKey = "测\\r试-中.~,+\"'*&￥#@%！（文）+字符|？-2.zip";
        final long partSize = 128 * 1024;     //128KB
        
        try {
            ossClient.createBucket(sourceBucket);
            ossClient.createBucket(targetBucket);
            
            // Put object into source bucket
            final long inputStreamLength = partSize * 4;
            try {
                InputStream instream = genFixedLengthInputStream(inputStreamLength);
                ossClient.putObject(sourceBucket, sourceKey, instream, null);
            } catch (Exception e) {
                Assertions.fail(e.getMessage());
            }
            
            // Claim upload id for target bucket 
            String uploadId = claimUploadId(ossClient, targetBucket, targetKey);
            
            // Upload part copy
            final int partNumber = 1;
            final long beginIndex = partSize;
            List<PartETag> partETags = new ArrayList<PartETag>();
            UploadPartCopyRequest uploadPartCopyRequest = 
                    new UploadPartCopyRequest(sourceBucket, sourceKey, targetBucket, targetKey);
            uploadPartCopyRequest.setPartNumber(partNumber);
            uploadPartCopyRequest.setUploadId(uploadId);
            uploadPartCopyRequest.setBeginIndex(beginIndex);
            uploadPartCopyRequest.setPartSize(partSize);
            UploadPartCopyResult uploadPartCopyResult = ossClient.uploadPartCopy(uploadPartCopyRequest);
            partETags.add(uploadPartCopyResult.getPartETag());
            Assertions.assertEquals(partNumber, uploadPartCopyResult.getPartNumber());
            Assertions.assertEquals(uploadPartCopyResult.getRequestId().length(), REQUEST_ID_LEN);
            
            ListPartsRequest listPartsRequest = new ListPartsRequest(targetBucket, targetKey, uploadId);
            PartListing partListing = ossClient.listParts(listPartsRequest);
            Assertions.assertEquals(1, partListing.getParts().size());
            Assertions.assertEquals(targetBucket, partListing.getBucketName());
            Assertions.assertEquals(targetKey, partListing.getKey());
            Assertions.assertEquals(uploadId, partListing.getUploadId());
            Assertions.assertEquals(LIST_PART_MAX_RETURNS, partListing.getMaxParts().intValue());
            Assertions.assertNotNull(partListing.getNextPartNumberMarker());
            Assertions.assertFalse(partListing.isTruncated());
            Assertions.assertEquals(partListing.getRequestId().length(), REQUEST_ID_LEN);
            
            // Complete multipart upload
            CompleteMultipartUploadRequest completeMultipartUploadRequest = 
                    new CompleteMultipartUploadRequest(targetBucket, targetKey, uploadId, partETags);
            CompleteMultipartUploadResult completeMultipartUploadResult =
                    ossClient.completeMultipartUpload(completeMultipartUploadRequest);
            Assertions.assertEquals(composeLocation(ossClient, OSS_TEST_ENDPOINT, targetBucket, targetKey), 
                    completeMultipartUploadResult.getLocation());
            Assertions.assertEquals(targetBucket, completeMultipartUploadResult.getBucketName());
            Assertions.assertEquals(targetKey, completeMultipartUploadResult.getKey());
            Assertions.assertEquals(calcMultipartsETag(partETags), completeMultipartUploadResult.getETag());
            Assertions.assertEquals(completeMultipartUploadResult.getRequestId().length(), REQUEST_ID_LEN);
            
            // Get uploaded object
            OSSObject o = ossClient.getObject(targetBucket, targetKey);
            final long objectSize = 1 * partSize;
            Assertions.assertEquals(objectSize, o.getObjectMetadata().getContentLength());
            Assertions.assertEquals(calcMultipartsETag(partETags), o.getObjectMetadata().getETag());
            Assertions.assertEquals(o.getRequestId().length(), REQUEST_ID_LEN);
        } catch (Exception e) {
            Assertions.fail(e.getMessage());
        } finally {
            deleteBucketWithObjects(ossClient, sourceBucket);
            deleteBucketWithObjects(ossClient, targetBucket);
        }
    }
    
    @Test
    public void testUnormalUploadPartCopyByRange() {
        final String sourceBucket = super.bucketName + "-" + "unormal-upload-part-copy-range-source";
        final String targetBucket = super.bucketName + "-" + "unormal-upload-part-copy-range-target";
        final String sourceKey = "unormal-upload-part-copy-by-range-object-source";
        final String targetKey = "unormal-upload-part-copy-by-range-object-target";
        final long partSize = 128 * 1024;     //128KB
        
        try {
            ossClient.createBucket(sourceBucket);
            ossClient.createBucket(targetBucket);
            
            // Put object into source bucket
            final long inputStreamLength = partSize * 4;
            String eTag = null;
            try {
                InputStream instream = genFixedLengthInputStream(inputStreamLength);
                PutObjectResult result = ossClient.putObject(sourceBucket, sourceKey, instream, null);
                eTag = result.getETag();
            } catch (Exception e) {
                Assertions.fail(e.getMessage());
            }
            
            // Claim upload id for target bucket 
            String uploadId = claimUploadId(ossClient, targetBucket, targetKey);
            
            // Upload part copy with invalid copy range
            final int partNumber = 1;
            final long beginIndex = partSize;
            List<PartETag> partETags = new ArrayList<PartETag>();
            UploadPartCopyRequest uploadPartCopyRequest = 
                    new UploadPartCopyRequest(sourceBucket, sourceKey, targetBucket, targetKey);
            uploadPartCopyRequest.setPartNumber(partNumber);
            uploadPartCopyRequest.setUploadId(uploadId);
            uploadPartCopyRequest.setBeginIndex(beginIndex);
            // Illegal copy range([beginIndex, begin + inputStreamLength]), just copy entire object
            uploadPartCopyRequest.setPartSize(inputStreamLength);
            UploadPartCopyResult uploadPartCopyResult = ossClient.uploadPartCopy(uploadPartCopyRequest);
            partETags.add(uploadPartCopyResult.getPartETag());
            Assertions.assertEquals(eTag, uploadPartCopyResult.getETag());
            Assertions.assertEquals(partNumber, uploadPartCopyResult.getPartNumber());
            Assertions.assertEquals(uploadPartCopyResult.getRequestId().length(), REQUEST_ID_LEN);
            
            ListPartsRequest listPartsRequest = new ListPartsRequest(targetBucket, targetKey, uploadId);
            PartListing partListing = ossClient.listParts(listPartsRequest);
            Assertions.assertEquals(1, partListing.getParts().size());
            Assertions.assertEquals(targetBucket, partListing.getBucketName());
            Assertions.assertEquals(targetKey, partListing.getKey());
            Assertions.assertEquals(uploadId, partListing.getUploadId());
            Assertions.assertEquals(LIST_PART_MAX_RETURNS, partListing.getMaxParts().intValue());
            Assertions.assertNotNull(partListing.getNextPartNumberMarker());
            Assertions.assertFalse(partListing.isTruncated());
            Assertions.assertEquals(partListing.getRequestId().length(), REQUEST_ID_LEN);
            
            // Complete multipart upload
            CompleteMultipartUploadRequest completeMultipartUploadRequest = 
                    new CompleteMultipartUploadRequest(targetBucket, targetKey, uploadId, partETags);
            CompleteMultipartUploadResult completeMultipartUploadResult =
                    ossClient.completeMultipartUpload(completeMultipartUploadRequest);
            Assertions.assertEquals(composeLocation(ossClient, OSS_TEST_ENDPOINT, targetBucket, targetKey), 
                    completeMultipartUploadResult.getLocation());
            Assertions.assertEquals(targetBucket, completeMultipartUploadResult.getBucketName());
            Assertions.assertEquals(targetKey, completeMultipartUploadResult.getKey());
            Assertions.assertEquals(calcMultipartsETag(partETags), completeMultipartUploadResult.getETag());
            Assertions.assertEquals(completeMultipartUploadResult.getRequestId().length(), REQUEST_ID_LEN);
            
            // Get uploaded object
            OSSObject o = ossClient.getObject(targetBucket, targetKey);
            final long objectSize = inputStreamLength;
            Assertions.assertEquals(objectSize, o.getObjectMetadata().getContentLength());
            Assertions.assertEquals(calcMultipartsETag(partETags), o.getObjectMetadata().getETag());
            Assertions.assertEquals(o.getRequestId().length(), REQUEST_ID_LEN);
        } catch (Exception e) {
            Assertions.fail(e.getMessage());
        } finally {
            deleteBucketWithObjects(ossClient, sourceBucket);
            deleteBucketWithObjects(ossClient, targetBucket);
        }
    }
}
