package com.example.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.model.Invoice;
import com.example.model.InvoiceDetail;
import com.example.model.InvoicePreviewResponseDTO;
import com.example.model.InvoiceResponseDTO;
import com.example.repository.InvoiceDetailRepository;
import com.example.repository.InvoiceRepository;
import com.example.service.IInvoiceService;
import com.example.service.InvoicePdfService;

@RestController
@RequestMapping("/invoice")
public class InvoiceController {

    @Autowired
    private IInvoiceService invoiceService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceDetailRepository invoiceDetailRepository;

    @Autowired
    private InvoicePdfService invoicePdfService;

   
    @GetMapping("/preview/{userId}")
    public ResponseEntity<InvoicePreviewResponseDTO> previewInvoice(
            @PathVariable Integer userId) {
        return ResponseEntity.ok(invoiceService.previewInvoice(userId));
    }

    
    @PostMapping("/generate/{userId}")
    public InvoiceResponseDTO generateInvoice(@PathVariable Integer userId) {
        return invoiceService.generateInvoice(userId);
    }

    
    @GetMapping("/customer/{userId}")
    public List<Invoice> getInvoicesByCustomer(@PathVariable Integer userId) {
        return invoiceService.getInvoicesByCustomer(userId);
    }

    
    @GetMapping("/id/{invoiceId}")
    public Invoice getInvoiceById(@PathVariable Integer invoiceId) {
        return invoiceService.getInvoiceById(invoiceId);
    }

    
    @GetMapping("/download/{invoiceId}")
    public ResponseEntity<byte[]> downloadInvoice(
            @PathVariable Integer invoiceId) {

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        List<InvoiceDetail> details
                = invoiceDetailRepository.findByInvoiceInvoiceId(invoiceId);

        byte[] pdfBytes
                = invoicePdfService.generateInvoicePdf(invoice, details);

        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=Invoice_INV-" + invoiceId + ".pdf")
                .header("Content-Type", "application/pdf")
                .body(pdfBytes);
    }
}
