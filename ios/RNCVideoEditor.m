#import <Foundation/Foundation.h>
#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(RNCVideoEditor, NSObject)

RCT_EXTERN_METHOD(separateAudioVideo:(NSString *)videoPath
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(mergeAudioWithVideo:(NSString *)videoPath
                  audioPath:(NSString *)audioPath
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

@end
