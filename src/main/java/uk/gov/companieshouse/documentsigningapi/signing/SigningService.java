package uk.gov.companieshouse.documentsigningapi.signing;

import org.apache.commons.io.FileUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;
import uk.gov.companieshouse.documentsigningapi.coversheet.VisualSignature;
import uk.gov.companieshouse.documentsigningapi.exception.DocumentSigningException;
import uk.gov.companieshouse.documentsigningapi.exception.DocumentUnavailableException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Calendar;

@Service
public class SigningService {

    private static final String SIGNING_AUTHORITY_NAME = "Companies House";

    private final String keystoreType;
    private final String keystorePath;
    private final String keystorePassword;
    private final String certificateAlias;

    private final VisualSignature visualSignature;

    public SigningService(@Value("${environment.keystore.type}") String keystoreType,
                          @Value("${environment.keystore.path}") String keystorePath,
                          @Value("${environment.keystore.password}") String keystorePassword,
                          @Value("${environment.certificate.alias}") String certificateAlias,
                          VisualSignature visualSignature) {
        this.keystoreType = keystoreType;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.certificateAlias = certificateAlias;
        this.visualSignature = visualSignature;
    }

    public byte[] signPDF(byte[] pdfToSign) throws DocumentSigningException, DocumentUnavailableException {
        try {
            KeyStore keyStore = getKeyStore();
            Signature signature = new Signature(keyStore, this.keystorePassword.toCharArray(), certificateAlias);

            // Create new PDF file
            File pdfFile = File.createTempFile("pdf", "");
            // Write the content to the new PDF
            FileUtils.writeByteArrayToFile(pdfFile, pdfToSign);

            // Create the file to be signed
            File signedPdf = File.createTempFile("signedPdf", "");

            // Sign the document
            this.signDetached(signature, pdfFile, signedPdf);

            byte[] signedPdfBytes = Files.readAllBytes(signedPdf.toPath());

            //remove temporary files
            pdfFile.deleteOnExit();
            signedPdf.deleteOnExit();

            return signedPdfBytes;


        } catch (NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException | KeyStoreException e) {
            throw new DocumentSigningException("Failed to obtain proper KeyStore or Certificate", e);
        } catch (IOException e) {
            throw new DocumentUnavailableException("Unable to load Keystore or Certificate", e);
        }
    }

    private KeyStore getKeyStore() throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException {
        KeyStore keyStore = KeyStore.getInstance(keystoreType);
        File key = ResourceUtils.getFile(keystorePath);
        keyStore.load(new FileInputStream(key), keystorePassword.toCharArray());
        return keyStore;
    }

    private void signDetached(SignatureInterface signature, File inputFile, File outputFile) throws IOException {
        if (inputFile == null || !inputFile.exists()) {
            throw new FileNotFoundException("Document for signing does not exist");
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile);
             PDDocument doc = PDDocument.load(inputFile)) {
            signDetached(signature, doc, fos);
        }
    }

    private void signDetached(SignatureInterface signature, PDDocument document, OutputStream output) throws IOException {
        PDSignature pdSignature = new PDSignature();
        pdSignature.setName(SIGNING_AUTHORITY_NAME);
        pdSignature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        pdSignature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);

        // the signing date, needed for valid signature
        pdSignature.setSignDate(Calendar.getInstance());

        try (final var signatureOptions = new SignatureOptions()) {

            visualSignature.render(pdSignature, signatureOptions, document);

            // register signature dictionary, signature interface and options
            document.addSignature(pdSignature, signature, signatureOptions);

            // write incremental (only for signing purpose)
            // use saveIncremental to add signature, using plain save method may break up a document
            document.saveIncremental(output);
        }

    }

}
