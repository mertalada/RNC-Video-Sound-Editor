Created by  MERT ALADAĞ
https://github.com/mertalada

# IOS Avfoundation and Android MediaCodec Native Module

# Getting Started

### For Android
  ## Step 1: Modify android/app/src/main/java/com/yourappname/MainApplication.kt
  
        import org.reactnative.camera.RNCVideoEditorPackage
        
        override fun getPackages(): List<ReactPackage> =
            PackageList(this).packages.apply {
              add(RNCVideoEditorPackage()) // << BURAYI EKLEDİN
            }
   
          
  ## Step 2: Run the following commands
        Remove Pods and Podfile.lock
            rm -rf ios/Pods
            rm -f ios/Podfile.lock

        Remove node_modules
            rm -rf node_modules

        Optional: Remove derived data (Xcode cache)
            rm -rf ~/Library/Developer/Xcode/DerivedData

        Reinstall everything
            npm install or yarn install
            cd ios
            pod install
            cd ..
            and start

-------------------------------------------------------
-ios
    -RNCVideoEditor.h
    -RNCVideoEditor.m
    -RNCVideoEditor.swift
    -Bridging-Header.h
    
-android
    -app/src/main/java/com/yourappname
                                    -RNCVideoEditorModule.kt
                                    -RNCVideoEditorPackage.kt
--------------------------------------------------------------------

## Frontend import and use package

    import { NativeModules, Platform } from 'react-native';
    import { Buffer } from 'buffer';
    import Sound from 'react-native-sound';
    import axios from 'axios';
    
    const { RNCVideoEditor } = NativeModules;
    
     const replaceAudioAndMux = async (videoPath, selectedClone) => {
        console.log('🟢 [Başlangıç] İşlem başlatıldı.');
        console.log('🎥 [Video Yolu] ' + videoPath);
        
        try {
          console.log('🟢 [1. Adım] Videodan sesi çıkarma işlemi başlıyor...');
          const extractedAudioPath = await RNCVideoEditor.separateAudioVideo(videoPath);
          console.log('✅ [1. Adım] Ses çıkarıldı: ' + extractedAudioPath);
      
          console.log('🟢 [2. Adım] Ses dosyası okunuyor (base64)...');
          const audioData = await RNFS.readFile(extractedAudioPath, 'base64');
          console.log('✅ [2. Adım] Ses dosyası okundu, boyut: ' + audioData.length);
      
          const formData = new FormData();
          formData.append('file', {
            uri: 'file://' + extractedAudioPath,
            type: 'audio/m4a',
            name: 'input.m4a',
          });
      
          console.log('🟢 [3. Adım] API çağrısı başlatılıyor...');
          console.log('🟢 [FormData İçeriği] Gönderilecek dosya: ' + extractedAudioPath);
      
          const response = await axios.post(
            `API URL`,
            formData,
            {
              headers: {
                'xi-api-key': 'API KEY',
                'Content-Type': 'multipart/form-data',
              },
              responseType: 'arraybuffer',
            }
          );
      
          console.log('✅ [3. Adım] API yanıtı alındı, boyut: ' + response.data.byteLength);
      
          const newAudioPath = `${RNFS.TemporaryDirectoryPath}new_audio.mp3`;
          console.log('🟢 [4. Adım] Yeni ses dosyası kaydediliyor: ' + newAudioPath);
          await RNFS.writeFile(newAudioPath, Buffer.from(response.data), 'base64');
          console.log('✅ [4. Adım] Yeni ses kaydedildi.');
      
          console.log('🟢 [5. Adım] Ses ve video birleştiriliyor...');
          const mergedVideoPath = await RNCVideoEditor.mergeAudioWithVideo(videoPath, newAudioPath);
          console.log('✅ [5. Adım] Birleştirme tamamlandı: ' + mergedVideoPath);
      
          console.log('✅ [Tamamlandı] İşlem başarıyla tamamlandı.');
          return mergedVideoPath;
      
        } catch (error) {
          console.error('❌ [HATA] İşlem sırasında hata oluştu:');
          if (error.response) {
            console.error('🔴 [API Hatası] Status:', error.response.status);
            console.error('🔴 [API Hatası] Data:', error.response.data);
          } else if (error.message) {
            console.error('🔴 [Genel Hata] Message:', error.message);
          } else {
            console.error('🔴 [Bilinmeyen Hata]', error);
          }
          return null;
        }
      };
      
      // apinizin istediği ses formatına göre düzenleyin m4a var
      // kendi apinize uygun bir formdata hazırlayın bu örnek.

-----------------------------------------------------------
